package com.zaza.housing.controller;

import com.zaza.housing.model.UserAccount;
import com.zaza.housing.service.AuthService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminController {
    private final AuthService users;

    public AdminController(AuthService users) { this.users = users; }

    @GetMapping
    public List<UserAccount> list(@RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return users.listUsers(limit, offset);
    }
}
