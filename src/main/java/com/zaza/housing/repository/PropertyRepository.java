package com.zaza.housing.repository;

import com.zaza.housing.dto.PropertyDetails;
import com.zaza.housing.dto.UnitDetails;
import com.zaza.housing.enums.PropertyStatus;
import com.zaza.housing.enums.PropertyType;
import com.zaza.housing.enums.UnitAvailability;
import com.zaza.housing.enums.UnitStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.Property;
import com.zaza.housing.model.PropertyUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PropertyRepository {
    private static final RowMapper<Property> PROPERTY = (rs, row) -> new Property(
            rs.getObject("id", UUID.class), rs.getObject("owner_organization_id", UUID.class),
            rs.getObject("manager_organization_id", UUID.class), rs.getString("name"), rs.getString("description"),
            PropertyType.valueOf(rs.getString("type")), rs.getString("country_code"), rs.getString("city"),
            rs.getString("address"), rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
            PropertyStatus.valueOf(rs.getString("status")), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    private static final RowMapper<PropertyUnit> UNIT = (rs, row) -> new PropertyUnit(
            rs.getObject("id", UUID.class), rs.getObject("property_id", UUID.class), rs.getString("label"),
            rs.getInt("bedrooms"), rs.getInt("bathrooms"), rs.getBigDecimal("floor_area_sqm"),
            rs.getBigDecimal("monthly_rent"), rs.getBigDecimal("deposit"), rs.getString("currency"),
            UnitAvailability.valueOf(rs.getString("availability")), UnitStatus.valueOf(rs.getString("status")),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    private final JdbcTemplate jdbc;

    public PropertyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID create(UUID owner, UUID actor, PropertyDetails details) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO properties(id, owner_organization_id, created_by, name, description, type,
                    country_code, city, address, latitude, longitude)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, owner, actor, details.name().trim(), description(details), details.type().name(),
                details.countryCode().toUpperCase(Locale.ROOT), details.city().trim(), details.address().trim(),
                details.latitude(), details.longitude());
        return id;
    }

    public Optional<Property> findInOrganization(UUID organization, UUID id, boolean lock) {
        return jdbc.query("""
                SELECT * FROM properties WHERE id = ?
                AND (owner_organization_id = ? OR manager_organization_id = ?)
                """ + (lock ? " FOR UPDATE" : ""), PROPERTY, id, organization, organization).stream().findFirst();
    }

    public Optional<Property> findByIdForUpdate(UUID id) {
        return jdbc.query("SELECT * FROM properties WHERE id = ? FOR UPDATE", PROPERTY, id).stream().findFirst();
    }

    public List<Property> list(UUID organization, PropertyStatus status, PropertyType type, String city, int limit, int offset) {
        return listScoped(organization,null,status,type,city,limit,offset);
    }
    public List<Property> listScoped(UUID organization,UUID agent,PropertyStatus status,PropertyType type,String city,int limit,int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM properties WHERE (owner_organization_id = ? OR manager_organization_id = ?)");
        List<Object> parameters = new ArrayList<>(List.of(organization, organization));
        if(agent!=null){sql.append(" AND EXISTS(SELECT 1 FROM agency_property_assignments a WHERE a.agency_id=? AND a.property_id=properties.id AND a.agent_id=? AND a.active AND agency_property_access(a.agency_id,a.property_id))");parameters.add(organization);parameters.add(agent);}
        if (status == null) sql.append(" AND status <> 'ARCHIVED'");
        else { sql.append(" AND status = ?"); parameters.add(status.name()); }
        if (type != null) { sql.append(" AND type = ?"); parameters.add(type.name()); }
        if (city != null) { sql.append(" AND lower(city) = lower(?)"); parameters.add(city.trim()); }
        sql.append(" ORDER BY created_at, id LIMIT ? OFFSET ?");
        parameters.add(limit); parameters.add(offset);
        return jdbc.query(sql.toString(), PROPERTY, parameters.toArray());
    }

    public void update(UUID id, long version, PropertyDetails details) {
        checkUpdated(jdbc.update("""
                UPDATE properties SET name = ?, description = ?, type = ?, country_code = ?, city = ?,
                    address = ?, latitude = ?, longitude = ?, version = version + 1, updated_at = clock_timestamp()
                WHERE id = ? AND version = ?
                """, details.name().trim(), description(details), details.type().name(),
                details.countryCode().toUpperCase(Locale.ROOT), details.city().trim(), details.address().trim(),
                details.latitude(), details.longitude(), id, version));
    }

    public void changeStatus(UUID id, long version, PropertyStatus status) {
        checkUpdated(jdbc.update("UPDATE properties SET status = ?, version = version + 1, updated_at = clock_timestamp() WHERE id = ? AND version = ?",
                status.name(), id, version));
    }

    public void changeManagement(UUID id, long version, UUID agency) {
        checkUpdated(jdbc.update("UPDATE properties SET manager_organization_id = ?, version = version + 1, updated_at = clock_timestamp() WHERE id = ? AND version = ?",
                agency, id, version));
    }

    public boolean isAgency(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM organizations WHERE id = ? AND type = 'AGENCY')", Boolean.class, id));
    }

    public UUID createUnit(UUID property, UnitDetails details) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO property_units(id, property_id, label, bedrooms, bathrooms, floor_area_sqm,
                    monthly_rent, deposit, currency, availability) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, property, details.label().trim(), details.bedrooms(), details.bathrooms(), details.floorAreaSqm(),
                details.monthlyRent(), details.deposit(), details.currency().toUpperCase(Locale.ROOT), details.availability().name());
        return id;
    }

    public Optional<PropertyUnit> unit(UUID property, UUID id) {
        return jdbc.query("SELECT * FROM property_units WHERE property_id = ? AND id = ?", UNIT, property, id).stream().findFirst();
    }

    public List<PropertyUnit> units(UUID property, UnitAvailability availability, boolean includeArchived, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM property_units WHERE property_id = ?");
        List<Object> parameters = new ArrayList<>(List.of(property));
        if (!includeArchived) sql.append(" AND status = 'ACTIVE'");
        if (availability != null) { sql.append(" AND availability = ?"); parameters.add(availability.name()); }
        sql.append(" ORDER BY created_at, id LIMIT ? OFFSET ?");
        parameters.add(limit); parameters.add(offset);
        return jdbc.query(sql.toString(), UNIT, parameters.toArray());
    }

    public void updateUnit(UUID property, UUID id, long version, UnitDetails details) {
        checkUpdated(jdbc.update("""
                UPDATE property_units SET label = ?, bedrooms = ?, bathrooms = ?, floor_area_sqm = ?,
                    monthly_rent = ?, deposit = ?, currency = ?, availability = ?,
                    version = version + 1, updated_at = clock_timestamp()
                WHERE property_id = ? AND id = ? AND version = ?
                """, details.label().trim(), details.bedrooms(), details.bathrooms(), details.floorAreaSqm(),
                details.monthlyRent(), details.deposit(), details.currency().toUpperCase(Locale.ROOT),
                details.availability().name(), property, id, version));
    }

    public void changeUnitStatus(UUID property, UUID id, long version, UnitStatus status) {
        checkUpdated(jdbc.update("UPDATE property_units SET status = ?, version = version + 1, updated_at = clock_timestamp() WHERE property_id = ? AND id = ? AND version = ?",
                status.name(), property, id, version));
    }

    private static String description(PropertyDetails details) {
        return details.description() == null ? "" : details.description().trim();
    }

    private static void checkUpdated(int count) {
        if (count != 1) throw new ApiException(HttpStatus.CONFLICT, "The record has changed. Reload it before saving.");
    }
}
