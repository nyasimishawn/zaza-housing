package com.zaza.housing.auth;

import com.zaza.housing.organization.OrganizationService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminController {
    private final AuthRepository users;

    public AdminController(AuthRepository users) { this.users = users; }

    @GetMapping
    public List<UserAccount> list(@RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        OrganizationService.validatePage(limit, offset);
        return users.listUsers(limit, offset);
    }
}
