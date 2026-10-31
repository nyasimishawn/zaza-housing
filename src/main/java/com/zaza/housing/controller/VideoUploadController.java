package com.zaza.housing.controller;

import com.zaza.housing.dto.VideoJobActionRequest;
import com.zaza.housing.dto.VideoUploadRequest;
import com.zaza.housing.dto.VideoUploadResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.VideoUploadService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/properties/{propertyId}/video-uploads")
public class VideoUploadController {
  private final VideoUploadService jobs;
  public VideoUploadController(VideoUploadService jobs){this.jobs=jobs;}
  @PostMapping(consumes="multipart/form-data") @ResponseStatus(HttpStatus.ACCEPTED)
  public VideoUploadResponse upload(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,
      @PathVariable UUID propertyId,@RequestPart("file") MultipartFile file,@Valid @RequestPart("metadata") VideoUploadRequest metadata){return jobs.enqueue(actor,organizationId,propertyId,metadata,file);}
  @GetMapping public List<VideoUploadResponse> list(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,
      @PathVariable UUID propertyId,@RequestParam(defaultValue="20")int limit,@RequestParam(defaultValue="0")int offset){return jobs.list(actor,organizationId,propertyId,limit,offset);}
  @GetMapping("/{id}") public VideoUploadResponse get(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,@PathVariable UUID propertyId,@PathVariable UUID id){return jobs.get(actor,organizationId,propertyId,id);}
  @PostMapping("/{id}/cancel") public VideoUploadResponse cancel(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,
      @PathVariable UUID propertyId,@PathVariable UUID id,@Valid @RequestBody VideoJobActionRequest request){return jobs.action(actor,organizationId,propertyId,id,request,false);}
  @PostMapping("/{id}/retry") public VideoUploadResponse retry(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,
      @PathVariable UUID propertyId,@PathVariable UUID id,@Valid @RequestBody VideoJobActionRequest request){return jobs.action(actor,organizationId,propertyId,id,request,true);}
}
