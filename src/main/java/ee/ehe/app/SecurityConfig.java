package ee.ehe.app;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      Map<String, Object> realm = jwt.getClaimAsMap("realm_access");
      Object roles = realm == null ? null : realm.get("roles");
      if (!(roles instanceof List<?> list)) return List.of();
      return list
        .stream()
        .filter(String.class::isInstance)
        .map(x -> new SimpleGrantedAuthority("ROLE_" + x))
        .collect(java.util.stream.Collectors.toList());
    });
    return http
      .cors(c -> {})
      .csrf(c -> c.disable())
      .sessionManagement(c ->
        c.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
      )
      .authorizeHttpRequests(a ->
        a
          .requestMatchers("/actuator/health")
          .permitAll()
          .requestMatchers(
            org.springframework.http.HttpMethod.GET,
            "/api/site/public/**"
          )
          .permitAll()
          .requestMatchers(
            org.springframework.http.HttpMethod.POST,
            "/api/site/public/bookings"
          )
          .permitAll()
          .requestMatchers("/api/site/admin/**")
          .hasRole("SITE_ADMIN")
          .requestMatchers("/api/weddings/**", "/api/weddings")
          .hasAnyRole("PLANNER", "CLIENT")
          .anyRequest()
          .denyAll()
      )
      .oauth2ResourceServer(o ->
        o.jwt(j -> j.jwtAuthenticationConverter(converter))
      )
      .build();
  }

  @Bean
  JwtDecoder decoder(
    @Value("${ehe.issuer}") String issuer,
    @Value("${ehe.jwk-uri}") String jwk,
    @Value("${ehe.audience}") String audience
  ) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwk).build();
    OAuth2TokenValidator<Jwt> aud = jwt ->
      jwt.getAudience().contains(audience)
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "Wrong audience", null)
          );
    decoder.setJwtValidator(
      new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer),
        aud
      )
    );
    return decoder;
  }

  @Bean
  CorsConfigurationSource cors(@Value("${ehe.cors-origins}") String origins) {
    CorsConfiguration c = new CorsConfiguration();
    c.setAllowedOrigins(Arrays.asList(origins.split(",")));
    c.setAllowedMethods(
      List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
    );
    c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    c.setAllowCredentials(false);
    UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
    s.registerCorsConfiguration("/**", c);
    return s;
  }
}
