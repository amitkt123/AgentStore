package com.agentstore.platform.identity;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(OrgRepository orgRepository, UserRepository userRepository, JwtService jwtService) {
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        Org org = orgRepository.save(new Org(null, request.orgName(), request.orgType()));
        User user = userRepository.save(new User(null, org, request.email(),
                passwordEncoder.encode(request.password()), request.role()));
        return ResponseEntity.ok(new AuthResponse(jwtService.issue(user.getId(), org.getId(), user.getRole())));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return userRepository.findByEmail(request.email())
                .filter(user -> passwordEncoder.matches(request.password(), user.getPasswordHash()))
                .map(user -> ResponseEntity.ok(new AuthResponse(
                        jwtService.issue(user.getId(), user.getOrg().getId(), user.getRole()))))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }
}
