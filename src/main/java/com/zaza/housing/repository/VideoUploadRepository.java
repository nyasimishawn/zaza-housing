package com.zaza.housing.repository;

import com.zaza.housing.dto.VideoUploadRequest;
import com.zaza.housing.model.StagedVideo;
import com.zaza.housing.model.VideoUploadJob;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class VideoUploadRepository {
  private final JdbcTemplate jdbc;
  private static final RowMapper<VideoUploadJob> ROW=(r,n)->new VideoUploadJob(
      r.getObject("id",UUID.class),r.getObject("property_id",UUID.class),r.getObject("organization_id",UUID.class),
      r.getObject("actor_id",UUID.class),r.getObject("request_id",UUID.class),r.getLong("property_version"),
      r.getString("caption"),r.getString("source_key"),r.getString("source_hash"),r.getLong("source_size"),
      r.getBoolean("source_deleted"),r.getString("status"),r.getLong("version"),r.getInt("attempts"),
      r.getInt("progress"),r.getString("failure_code"),r.getObject("video_id",UUID.class),r.getObject("lease_id",UUID.class),
      r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
  public VideoUploadRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
  public Optional<VideoUploadJob> find(UUID property,UUID id,boolean lock){
    return jdbc.query("SELECT * FROM property_video_uploads WHERE property_id=? AND id=?"+(lock?" FOR UPDATE":""),ROW,property,id).stream().findFirst();
  }
  public Optional<VideoUploadJob> replay(UUID actor,UUID property,UUID request){
    return jdbc.query("SELECT * FROM property_video_uploads WHERE actor_id=? AND property_id=? AND request_id=?",ROW,actor,property,request).stream().findFirst();
  }
  public void create(UUID id,UUID organization,UUID property,UUID actor,VideoUploadRequest request,StagedVideo source){
    jdbc.update("INSERT INTO property_video_uploads(id,organization_id,property_id,actor_id,request_id,property_version,caption,source_key,source_hash,source_size) VALUES(?,?,?,?,?,?,?,?,?,?)",
        id,organization,property,actor,request.requestId(),request.propertyVersion(),request.caption().strip(),source.key(),source.hash(),source.size());
  }
  public List<VideoUploadJob> list(UUID property,int limit,int offset){
    return jdbc.query("SELECT * FROM property_video_uploads WHERE property_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",ROW,property,limit,offset);
  }
  public int pending(UUID property){return jdbc.queryForObject("SELECT count(*) FROM property_video_uploads WHERE property_id=? AND status IN ('QUEUED','PROCESSING')",Integer.class,property);}
  public int recent(UUID actor){return jdbc.queryForObject("SELECT count(*) FROM property_video_uploads WHERE actor_id=? AND created_at>clock_timestamp()-interval '1 hour'",Integer.class,actor);}
  public Optional<VideoUploadJob> claim(){
    return jdbc.query("""
        WITH next AS (SELECT id FROM property_video_uploads WHERE status='QUEUED'
          ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED)
        UPDATE property_video_uploads j SET status='PROCESSING',version=version+1,attempts=attempts+1,
          progress=0,lease_id=gen_random_uuid(),started_at=clock_timestamp(),updated_at=clock_timestamp(),failure_code=NULL
        FROM next WHERE j.id=next.id RETURNING j.*
        """,ROW).stream().findFirst();
  }
  public void progress(VideoUploadJob job,int progress){
    jdbc.update("UPDATE property_video_uploads SET progress=GREATEST(progress,?) WHERE id=? AND lease_id=? AND status='PROCESSING'",Math.clamp(progress,0,99),job.id(),job.leaseId());
  }
  public boolean finish(VideoUploadJob job,UUID video,String code){
    return jdbc.update("UPDATE property_video_uploads SET status=?,video_id=?,failure_code=?,progress=?,version=version+1,finished_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=? AND lease_id=? AND status='PROCESSING'",
        video==null?"FAILED":"READY",video,code,video==null?job.progress():100,job.id(),job.leaseId())==1;
  }
  public void cancel(UUID id){jdbc.update("UPDATE property_video_uploads SET status='CANCELLED',version=version+1,finished_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?",id);}
  public void retry(UUID id){jdbc.update("UPDATE property_video_uploads SET status='QUEUED',lease_id=NULL,failure_code=NULL,progress=0,version=version+1,updated_at=clock_timestamp() WHERE id=?",id);}
  public void expire(){jdbc.update("UPDATE property_video_uploads SET status='FAILED',failure_code='INTERRUPTED',version=version+1,finished_at=clock_timestamp(),updated_at=clock_timestamp() WHERE status='PROCESSING' AND started_at<clock_timestamp()-interval '10 minutes'");}
  public List<VideoUploadJob> cleanup(){return jdbc.query("SELECT * FROM property_video_uploads WHERE NOT source_deleted AND status IN ('READY','FAILED','CANCELLED') AND updated_at<clock_timestamp()-interval '24 hours' ORDER BY updated_at LIMIT 20 FOR UPDATE SKIP LOCKED",ROW);}
  public void sourceDeleted(UUID id){jdbc.update("UPDATE property_video_uploads SET source_deleted=TRUE WHERE id=?",id);}
}
