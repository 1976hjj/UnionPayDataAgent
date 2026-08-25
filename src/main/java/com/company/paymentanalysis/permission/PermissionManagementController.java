package com.company.paymentanalysis.permission;

import com.company.paymentanalysis.permission.DataPermissionRepository.UserDataScope;
import com.company.paymentanalysis.permission.PermissionManagementService.BatchDeleteScopesCommand;
import com.company.paymentanalysis.permission.PermissionManagementService.BatchUpdateScopeStatusCommand;
import com.company.paymentanalysis.permission.PermissionManagementService.CreateCompleteScopesCommand;
import com.company.paymentanalysis.permission.PermissionManagementService.ManagedDimension;
import com.company.paymentanalysis.permission.PermissionManagementService.UpdateScopeStatusCommand;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for the hidden BI Agent permission-management page. */
@RestController
@RequestMapping("/api/permission/management")
public class PermissionManagementController {

    private final PermissionManagementService service;

    public PermissionManagementController(PermissionManagementService service) {
        this.service = service;
    }

    @PostMapping("/authenticate")
    public ResponseEntity<Void> authenticate(@RequestBody AuthenticationRequest request, HttpSession session) {
        service.authenticate(request == null ? null : request.password(), session);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session")
    public AuthenticationState session(HttpSession session) {
        return new AuthenticationState(Boolean.TRUE.equals(
                session.getAttribute(PermissionManagementService.SESSION_ATTRIBUTE)));
    }

    @GetMapping("/metadata")
    public ManagementMetadata metadata(HttpSession session) {
        service.requireAuthentication(session);
        return new ManagementMetadata(service.dimensions());
    }

    @GetMapping("/scopes")
    public List<UserDataScope> scopes(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            HttpSession session) {
        service.requireAuthentication(session);
        return service.scopes(keyword, enabled);
    }

    @PostMapping("/scopes")
    public ResponseEntity<Void> createScopes(@RequestBody CreateCompleteScopesCommand request, HttpSession session) {
        service.requireAuthentication(session);
        service.createCompleteScopes(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PatchMapping("/scopes")
    public ResponseEntity<Void> updateScopeStatus(@RequestBody UpdateScopeStatusCommand request, HttpSession session) {
        service.requireAuthentication(session);
        service.updateScopeStatus(request);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/scopes/batch-status")
    public ResponseEntity<Void> updateScopeStatuses(
            @RequestBody BatchUpdateScopeStatusCommand request, HttpSession session) {
        service.requireAuthentication(session);
        service.updateScopeStatuses(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/scopes/batch-delete")
    public ResponseEntity<Void> deleteScopes(@RequestBody BatchDeleteScopesCommand request, HttpSession session) {
        service.requireAuthentication(session);
        service.deleteScopes(request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/scopes")
    public ResponseEntity<Void> deleteScope(
            @RequestParam String loginUsername,
            @RequestParam String dimensionId,
            @RequestParam String dimensionValue,
            HttpSession session) {
        service.requireAuthentication(session);
        service.deleteScope(loginUsername, dimensionId, dimensionValue);
        return ResponseEntity.noContent().build();
    }

    public record AuthenticationRequest(String password) {
    }

    public record AuthenticationState(boolean authenticated) {
    }

    public record ManagementMetadata(List<ManagedDimension> dimensions) {
    }
}
