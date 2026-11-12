package com.pfa.pack.controller.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pfa.pack.model.dto.AuthenticationRequest;
import com.pfa.pack.model.dto.AuthenticationResponse;
import com.pfa.pack.service.AuthenticationService;

@RestController
@RequestMapping(value = {"/app/api/authenticate"})
public class AuthenticationResourceV2 {
private final AuthenticationService authenticationService;
  @Autowired
public AuthenticationResourceV2(final AuthenticationService authenticationService) {
this.authenticationService = authenticationService;
  }
  @PostMapping(value = {"", "/"})
public ResponseEntity<AuthenticationResponse> authenticate(@RequestBody final AuthenticationRequest authenticationRequest) {
if (authenticationRequest == null || text(authenticationRequest, 0) == null || text(authenticationRequest, 0).trim().isEmpty() || text(authenticationRequest, 1) == null || text(authenticationRequest, 1).trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(this.authenticationService.authenticate(authenticationRequest));
  }
    private static String text(Object o, int n) {
        if (o == null) {
            return null;
        }
        int i = 0;
        for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
            if (f.getType() == String.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                if (i++ == n) {
                    try { f.setAccessible(true); return (String) f.get(o); } catch (Exception e) { return null; }
                }
            }
        }
        return null;
    }

    @PostMapping(value = {"/check"})
    public ResponseEntity<String> check(@RequestBody final AuthenticationRequest authenticationRequest) {
        return ResponseEntity.ok(String.valueOf(this.authenticationService.authenticate(authenticationRequest)));
    }

}
