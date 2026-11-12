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
try {
            return ResponseEntity.ok(this.authenticationService.authenticate(authenticationRequest));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
  }
}
