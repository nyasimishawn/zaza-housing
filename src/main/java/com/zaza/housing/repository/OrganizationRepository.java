package com.zaza.housing.repository;

import com.zaza.housing.enums.MembershipRole;
import com.zaza.housing.enums.OrganizationType;
import com.zaza.housing.model.Member;
import com.zaza.housing.model.Organization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationRepository {
    private static final RowMapper<Organization> ORGANIZATION = (rs, row) -> new Organization(
            rs.getObject("id", UUID.class), rs.getString("name"), OrganizationType.valueOf(rs.getString("type")),
            MembershipRole.valueOf(rs.getString("role")), rs.getTimestamp("created_at").toInstant());
    private static final RowMapper<Member> MEMBER = (rs, row) -> new Member(
            rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getString("display_name"),
            MembershipRole.valueOf(rs.getString("role")));
    private final JdbcTemplate jdbc;

    public OrganizationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Organization create(UUID user, String name, OrganizationType type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations(id, name, type, created_by) VALUES (?, ?, ?, ?)", id, name, type.name(), user);
        addMember(id, user, MembershipRole.OWNER);
        return findForUser(id, user).orElseThrow();
    }

    public List<Organization> listForUser(UUID user, int limit, int offset) {
        return jdbc.query("""
                SELECT o.id, o.name, o.type, o.created_at, m.role FROM organizations o
                JOIN organization_memberships m ON m.organization_id = o.id
                WHERE m.user_id = ? ORDER BY o.created_at, o.id LIMIT ? OFFSET ?
                """, ORGANIZATION, user, limit, offset);
    }

    public Optional<Organization> findForUser(UUID organization, UUID user) {
        return jdbc.query("""
                SELECT o.id, o.name, o.type, o.created_at, m.role FROM organizations o
                JOIN organization_memberships m ON m.organization_id = o.id
                WHERE o.id = ? AND m.user_id = ?
                """, ORGANIZATION, organization, user).stream().findFirst();
    }

    public void lock(UUID organization) {
        jdbc.query("SELECT id FROM organizations WHERE id = ? FOR UPDATE", (rs, row) -> rs.getObject("id", UUID.class), organization);
    }

    public List<Member> listMembers(UUID organization, int limit, int offset) {
        return jdbc.query("""
                SELECT m.user_id, u.email, u.display_name, m.role FROM organization_memberships m
                JOIN app_users u ON u.id = m.user_id WHERE m.organization_id = ?
                ORDER BY m.created_at, m.user_id LIMIT ? OFFSET ?
                """, MEMBER, organization, limit, offset);
    }

    public Optional<Member> member(UUID organization, UUID user) {
        return jdbc.query("""
                SELECT m.user_id, u.email, u.display_name, m.role FROM organization_memberships m
                JOIN app_users u ON u.id = m.user_id WHERE m.organization_id = ? AND m.user_id = ?
                """, MEMBER, organization, user).stream().findFirst();
    }

    public Optional<UUID> activeUserByEmail(String email) {
        return jdbc.query("SELECT id FROM app_users WHERE email = ? AND active", (rs, row) -> rs.getObject("id", UUID.class), email).stream().findFirst();
    }

    public void addMember(UUID organization, UUID user, MembershipRole role) {
        jdbc.update("INSERT INTO organization_memberships(organization_id, user_id, role) VALUES (?, ?, ?)", organization, user, role.name());
    }

    public void updateMember(UUID organization, UUID user, MembershipRole role) {
        jdbc.update("UPDATE organization_memberships SET role = ? WHERE organization_id = ? AND user_id = ?", role.name(), organization, user);
    }

    public void removeMember(UUID organization, UUID user) {
        jdbc.update("DELETE FROM organization_memberships WHERE organization_id = ? AND user_id = ?", organization, user);
    }

    public void rename(UUID organization, String name) {
        jdbc.update("UPDATE organizations SET name = ? WHERE id = ?", name, organization);
    }
}
