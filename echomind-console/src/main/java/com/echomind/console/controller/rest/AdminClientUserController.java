package com.echomind.console.controller.rest;

import com.echomind.console.users.ClientUserAdminDtos.ClientUserListResponse;
import com.echomind.console.users.ClientUserAdminDtos.ClientUserView;
import com.echomind.console.users.ClientUserAdminDtos.DeleteClientUserResponse;
import com.echomind.console.users.ClientUserAdminDtos.UpdateClientUserStatusRequest;
import com.echomind.console.users.ClientUserAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/client-users")
@RequiredArgsConstructor
public class AdminClientUserController {

    private final ClientUserAdminService userAdminService;

    @GetMapping
    public ClientUserListResponse list() {
        return userAdminService.list();
    }

    @PutMapping("/{userId}/status")
    public ClientUserView updateStatus(@PathVariable String userId,
                                       @RequestBody UpdateClientUserStatusRequest request) {
        return userAdminService.updateStatus(userId, request);
    }

    @DeleteMapping("/{userId}")
    public DeleteClientUserResponse delete(@PathVariable String userId) {
        return userAdminService.delete(userId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
