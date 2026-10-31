package com.zaza.housing.service;

import com.zaza.housing.dto.VideoJobActionRequest;
import com.zaza.housing.dto.VideoUploadRequest;
import com.zaza.housing.dto.VideoUploadResponse;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.VideoUploadJob;
import com.zaza.housing.repository.AuthRepository;
import com.zaza.housing.repository.LocalVideoStorageRepository;
import com.zaza.housing.repository.PropertyVideoRepository;
import com.zaza.housing.repository.VideoUploadRepository;
import com.zaza.housing.util.Pagination;
import com.zaza.housing.util.StoredVideoUpload;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class VideoUploadService {
  private final VideoUploadRepository jobs;
  private final PropertyService properties;
  private final PropertyVideoRepository videos;
  private final PropertyVideoService media;
  private final VideoProcessingService processing;
  private final LocalVideoStorageRepository storage;
  private final AuthRepository users;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final boolean enabled;
  private final Semaphore slots=new Semaphore(2);
  private final java.util.concurrent.ExecutorService workers=Executors.newFixedThreadPool(2);
  public VideoUploadService(VideoUploadRepository jobs,PropertyService properties,PropertyVideoRepository videos,
      PropertyVideoService media,VideoProcessingService processing,LocalVideoStorageRepository storage,
      AuthRepository users,AuditService audit,PlatformTransactionManager manager,Clock clock,
      @Value("${zaza.media.jobs.enabled:true}") boolean enabled){
    this.jobs=jobs;this.properties=properties;this.videos=videos;this.media=media;this.processing=processing;
    this.storage=storage;this.users=users;this.audit=audit;this.tx=new TransactionTemplate(manager);this.clock=clock;this.enabled=enabled;
  }

  public VideoUploadResponse enqueue(AuthenticatedUser actor,UUID org,UUID property,VideoUploadRequest request,MultipartFile file){
    if(file.isEmpty()||file.getSize()>VideoProcessingService.MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"Choose a non-empty MP4/MOV clip up to 50 MB.");
    tx.executeWithoutResult(s->{requireActor(actor.id());requireOpen(actor,org,property);});
    UUID id=UUID.randomUUID();
    final com.zaza.housing.model.StagedVideo source;
    try{source=storage.stage(file,id);}catch(IOException error){throw unavailable();}
    boolean[] committed={false};
    try{
      return tx.execute(s->{
        requireActor(actor.id());
        var p=requireOpen(actor,org,property);
        var previous=jobs.replay(actor.id(),property,request.requestId());
        if(previous.isPresent()){
          var old=previous.get();
          if(!old.organizationId().equals(org)||!old.sourceHash().equals(source.hash())||old.sourceSize()!=source.size()
              ||old.propertyVersion()!=request.propertyVersion()||!old.caption().equals(request.caption().strip()))
            throw conflict("This upload identity was already used for different content. Review a new upload.");
          return response(old);
        }
        if(p.version()!=request.propertyVersion())throw conflict("The property changed. Reload and review the upload.");
        capacity(property);
        if(jobs.recent(actor.id())>=20)throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"At most 20 video uploads per hour are allowed.");
        jobs.create(id,org,property,actor.id(),request,source);
        var job=find(property,id,false);
        audit.recordSnapshot(actor.id(),org,"PROPERTY_VIDEO_UPLOAD_QUEUED",id,null,response(job));
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization(){
              @Override public void afterCommit(){committed[0]=true;}
            });
        return response(job);
      });
    }finally{
      if(!committed[0])try{storage.removeSource(source.key());}catch(IOException ignored){}
    }
  }

  public List<VideoUploadResponse> list(AuthenticatedUser actor,UUID org,UUID property,int limit,int offset){
    properties.get(actor,org,property);Pagination.validate(limit,offset);
    return jobs.list(property,limit,offset).stream().map(this::response).toList();
  }
  public VideoUploadResponse get(AuthenticatedUser actor,UUID org,UUID property,UUID id){
    properties.get(actor,org,property);return response(find(property,id,false));
  }
  public VideoUploadResponse action(AuthenticatedUser actor,UUID org,UUID property,UUID id,VideoJobActionRequest request,boolean retry){
    var result = tx.execute(s->{
      requireActor(actor.id());properties.lockForManagement(actor,org,property);
      if(retry)requireOpen(actor,org,property);
      var job=find(property,id,true);
      if(job.version()!=request.version())throw conflict("The upload changed. Reload it before continuing.");
      if(retry){
        if(!retryAllowed(job))throw conflict("Choose a new file for this upload; it cannot be retried.");
        if(job.updatedAt().plusSeconds(10).isAfter(clock.instant()))throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"Wait ten seconds before retrying processing.");
        capacity(property);jobs.retry(id);
      }else{
        if(!List.of("QUEUED","PROCESSING").contains(job.status()))throw conflict("Only a pending upload can be cancelled.");
        jobs.cancel(id);
      }
      var changed=response(find(property,id,false));
      audit.recordSnapshot(actor.id(),org,retry?"PROPERTY_VIDEO_UPLOAD_RETRIED":"PROPERTY_VIDEO_UPLOAD_CANCELLED",id,response(job),changed);
      return changed;
    });
    if(!retry)try{tx.executeWithoutResult(s->{var job=find(property,id,true);try{storage.removeSource(job.sourceKey());jobs.sourceDeleted(id);}catch(IOException ignored){}});}catch(RuntimeException ignored){}
    return result;
  }

  @Scheduled(fixedDelayString="${zaza.media.jobs.poll-ms:3000}")
  public void poll(){
    if(!enabled)return;
    try{
      tx.executeWithoutResult(s->{jobs.expire();for(var job:jobs.cleanup())try{storage.removeSource(job.sourceKey());jobs.sourceDeleted(job.id());}catch(IOException ignored){}});
      for(int i=0;i<2&&slots.tryAcquire();i++){
        try {
          var next=tx.execute(s->jobs.claim());
          if(next==null||next.isEmpty()){slots.release();break;}
          workers.submit(()->{try{process(next.get());}finally{slots.release();}});
        } catch(RuntimeException failure) { slots.release();throw failure; }
      }
    }catch(RuntimeException ignored){/* Next bounded poll retries queue access; no private parser output is logged. */}
  }
  /** Deterministic synchronous scheduler entry for isolated integration tests and maintenance tooling. */
  public boolean processOne(){
    var next=tx.execute(s->jobs.claim());
    if(next==null||next.isEmpty())return false;
    process(next.get());return true;
  }
  private void process(VideoUploadJob job){
    Path workspace=null;
    try{
      var actor=requireActor(job.actorId());
      tx.executeWithoutResult(s->requireOpen(actor,job.organizationId(),job.propertyId()));
      Path source=storage.staged(job.sourceKey());
      if(java.nio.file.Files.size(source)!=job.sourceSize()||!hash(source).equals(job.sourceHash()))throw new ApiException(HttpStatus.BAD_REQUEST,"Stored upload is incomplete.");
      workspace=storage.createWorkspace();
      var video=processing.process(new StoredVideoUpload(source),workspace,p->{try{jobs.progress(job,p);}catch(RuntimeException ignored){}});
      tx.executeWithoutResult(s->{
        var active=requireActor(job.actorId());
        requireOpen(active,job.organizationId(),job.propertyId());
        var current=find(job.propertyId(),job.id(),true);
        if(!current.status().equals("PROCESSING")||!Objects.equals(current.leaseId(),job.leaseId()))return;
        var result=media.storeProcessed(active,job.organizationId(),job.propertyId(),video,job.caption());
        if(!jobs.finish(job,result.id(),null))throw conflict("The upload no longer owns its processing lease.");
        audit.recordSnapshot(job.actorId(),job.organizationId(),"PROPERTY_VIDEO_UPLOAD_READY",job.id(),null,response(find(job.propertyId(),job.id(),false)));
      });
    }catch(Exception failure){
      String code=failure instanceof ApiException api
          ? api.status()==HttpStatus.BAD_REQUEST||api.status()==HttpStatus.PAYLOAD_TOO_LARGE?"INVALID_MEDIA"
          : api.status()==HttpStatus.NOT_FOUND||api.status()==HttpStatus.FORBIDDEN||api.status()==HttpStatus.CONFLICT?"SOURCE_CHANGED":"PROCESSING_UNAVAILABLE"
          : "PROCESSING_UNAVAILABLE";
      try{tx.executeWithoutResult(s->{if(jobs.finish(job,null,code))audit.record(job.actorId(),job.organizationId(),"PROPERTY_VIDEO_UPLOAD_FAILED",job.id());});}catch(RuntimeException ignored){}
    }finally{
      if(workspace!=null)try{storage.removeWorkspace(workspace);}catch(IOException ignored){}
      try{tx.executeWithoutResult(s->{var current=find(job.propertyId(),job.id(),true);if(List.of("READY","CANCELLED").contains(current.status()))try{storage.removeSource(current.sourceKey());jobs.sourceDeleted(current.id());}catch(IOException ignored){}});}catch(RuntimeException ignored){}
    }
  }

  private com.zaza.housing.model.Property requireOpen(AuthenticatedUser actor,UUID org,UUID property){
    var p=properties.lockForManagement(actor,org,property);
    if(p.status()==com.zaza.housing.enums.PropertyStatus.ARCHIVED)throw conflict("Restore the property before uploading videos.");return p;
  }
  private AuthenticatedUser requireActor(UUID id){var user=users.findById(id).orElseThrow(()->new ApiException(HttpStatus.FORBIDDEN,"The uploader is no longer active."));return new AuthenticatedUser(user.id(),user.email(),user.platformRole());}
  private void capacity(UUID property){if(jobs.pending(property)>=3||videos.activeCount(property)+jobs.pending(property)>=5)throw conflict("A property allows five active videos and at most three pending uploads.");}
  private VideoUploadJob find(UUID property,UUID id,boolean lock){return jobs.find(property,id,lock).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Upload not found."));}
  private boolean retryAllowed(VideoUploadJob job){return job.status().equals("FAILED")&&!job.sourceDeleted()&&job.attempts()<3&&List.of("INTERRUPTED","PROCESSING_UNAVAILABLE").contains(job.failureCode());}
  private VideoUploadResponse response(VideoUploadJob j){return new VideoUploadResponse(j.id(),j.propertyId(),j.caption(),j.status(),j.version(),j.attempts(),j.progress(),j.failureCode(),j.videoId(),retryAllowed(j),j.createdAt(),j.updatedAt());}
  private String hash(Path file)throws IOException{try(var input=java.nio.file.Files.newInputStream(file)){var hash=java.security.MessageDigest.getInstance("SHA-256");byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)hash.update(b,0,n);return java.util.HexFormat.of().formatHex(hash.digest());}catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}}
  private static ApiException conflict(String message){return new ApiException(HttpStatus.CONFLICT,message);}
  private static ApiException unavailable(){return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"Video upload storage is unavailable.");}
  @PreDestroy public void close(){workers.shutdownNow();}
}
