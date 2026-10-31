package com.zaza.housing.repository;
import com.zaza.housing.model.PropertyDocumentLink;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
@Repository public class PropertyDocumentRepository {
 private final JdbcTemplate jdbc;
 private static final RowMapper<PropertyDocumentLink> ROW=(r,n)->new PropertyDocumentLink(r.getObject("id",UUID.class),r.getObject("organization_id",UUID.class),r.getObject("property_id",UUID.class),r.getObject("document_id",UUID.class),r.getObject("document_version_id",UUID.class),r.getBoolean("detached"),r.getTimestamp("created_at").toInstant());
 public PropertyDocumentRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public Optional<PropertyDocumentLink> find(UUID id){return jdbc.query("SELECT * FROM property_document_links WHERE id=?",ROW,id).stream().findFirst();}
 public List<PropertyDocumentLink> list(UUID org,UUID property,int limit,int offset){return jdbc.query("SELECT * FROM property_document_links WHERE organization_id=? AND property_id=? AND NOT detached ORDER BY created_at DESC,id LIMIT ? OFFSET ?",ROW,org,property,limit,offset);}
 public int count(UUID org,UUID property){return jdbc.queryForObject("SELECT count(*) FROM property_document_links WHERE organization_id=? AND property_id=? AND NOT detached",Integer.class,org,property);}
 public void create(UUID id,UUID org,UUID property,UUID doc,UUID version,UUID actor){jdbc.update("INSERT INTO property_document_links(id,organization_id,property_id,document_id,document_version_id,actor_id) VALUES(?,?,?,?,?,?)",id,org,property,doc,version,actor);}
 public void detach(UUID id){jdbc.update("UPDATE property_document_links SET detached=TRUE WHERE id=?",id);}
}
