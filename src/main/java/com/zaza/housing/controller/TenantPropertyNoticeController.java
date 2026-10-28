package com.zaza.housing.controller;
import com.zaza.housing.model.*;
import com.zaza.housing.dto.PropertyNoticeCommand;
import com.zaza.housing.service.PropertyNoticeService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/me/property-notices")
public class TenantPropertyNoticeController {
 private final PropertyNoticeService notices;
 public TenantPropertyNoticeController(PropertyNoticeService notices){this.notices=notices;}
 @GetMapping public List<PropertyNotice> list(@AuthenticationPrincipal AuthenticatedUser a,@RequestParam(defaultValue="50")int limit,@RequestParam(defaultValue="0")int offset){return notices.list(a,null,null,limit,offset);}
 @GetMapping("/{id}") public PropertyNotice get(@AuthenticationPrincipal AuthenticatedUser a,@PathVariable UUID id){return notices.get(a,null,null,id,false);}
 @GetMapping("/{id}/history") public List<CaretakerEvent> history(@AuthenticationPrincipal AuthenticatedUser a,@PathVariable UUID id,@RequestParam(defaultValue="50")int limit,@RequestParam(defaultValue="0")int offset){return notices.history(a,null,null,id,limit,offset);}
 @PostMapping("/{id}/{command}") public PropertyNotice command(@AuthenticationPrincipal AuthenticatedUser a,@PathVariable UUID id,@PathVariable String command,@RequestBody @Valid PropertyNoticeCommand r){return notices.command(a,null,null,id,command,r);}
}
