package com.zaza.housing.service;
import com.zaza.housing.dto.*;
import com.zaza.housing.model.*;
import com.zaza.housing.repository.PropertyDocumentRepository;
import com.zaza.housing.util.Pagination;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class PropertyDocumentService {
 private final PropertyDocumentRepository links;private final PropertyService properties;private final DocumentAccessService access;private final DocumentReadService reads;private final AuditService audit;
 public PropertyDocumentService(PropertyDocumentRepository links,PropertyService properties,DocumentAccessService access,DocumentReadService reads,AuditService audit){this.links=links;this.properties=properties;this.access=access;this.reads=reads;this.audit=audit;}
 public List<PropertyDocumentResponse> list(AuthenticatedUser actor,UUID org,UUID property,int limit,int offset){
  properties.get(actor,org,property);access.owner(actor,org,false);Pagination.validate(limit,offset);
  return links.list(org,property,limit,offset).stream().map(l->response(actor,org,l)).toList();
 }
 @Transactional public PropertyDocumentResponse attach(AuthenticatedUser actor,UUID org,UUID property,PropertyDocumentRequest request){
  var p=properties.lockForManagement(actor,org,property);access.owner(actor,org,true);
  var previous=links.find(request.requestId());
  if(previous.isPresent()){
   var l=previous.get();
   if(!l.organizationId().equals(org)||!l.propertyId().equals(property)||!l.documentId().equals(request.documentId())||!l.documentVersionId().equals(request.documentVersionId())||l.detached())throw DocumentAccessService.conflict("This link identity was already used. Reload property files.");
   return response(actor,org,l);
  }
  if(p.version()!=request.propertyVersion()||p.status()==com.zaza.housing.enums.PropertyStatus.ARCHIVED)throw DocumentAccessService.conflict("The property changed or is archived. Reload property files.");
  var document=access.own(actor,org,request.documentId(),false);access.active(document);access.version(document,request.documentVersionId());
  if(links.count(org,property)>=100)throw DocumentAccessService.conflict("A property workspace allows 100 linked documents.");
  links.create(request.requestId(),org,property,document.id(),request.documentVersionId(),actor.id());
  audit.record(actor.id(),org,"PROPERTY_DOCUMENT_LINKED",request.requestId());return response(actor,org,links.find(request.requestId()).orElseThrow());
 }
 @Transactional public void detach(AuthenticatedUser actor,UUID org,UUID property,UUID id){
  properties.lockForManagement(actor,org,property);access.owner(actor,org,true);var l=links.find(id).orElseThrow(DocumentAccessService::missing);
  if(!l.organizationId().equals(org)||!l.propertyId().equals(property))throw DocumentAccessService.missing();
  if(!l.detached()){links.detach(id);audit.record(actor.id(),org,"PROPERTY_DOCUMENT_UNLINKED",id);}
 }
 private PropertyDocumentResponse response(AuthenticatedUser actor,UUID org,PropertyDocumentLink link){
  var document=access.own(actor,org,link.documentId(),false);
  return new PropertyDocumentResponse(link.id(),reads.response(document),reads.response(access.version(document,link.documentVersionId())),link.createdAt());
 }
}
