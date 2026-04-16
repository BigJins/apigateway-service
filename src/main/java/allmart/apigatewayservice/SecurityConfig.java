package allmart.apigatewayservice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;

/**
 * Gateway 인증·인가 설정
 *
 * 인증: auth-service JWKS 엔드포인트에서 RSA 공개키 취득 → RS256 로컬 검증
 *   - JWKS 캐시 → auth-service 장애 시에도 검증 가능
 *   - 신규 kid 감지 시 자동 재취득
 *
 * 인가: JWT type claim → ROLE_MEMBER / ROLE_CUSTOMER 권한 매핑
 *   - CUSTOMER: 주문 생성, 상품 조회
 *   - MEMBER:   상품 관리(등록/수정/삭제)
 *   - /internal/**: 외부 완전 차단 (서비스 간 내부 호출 전용)
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:5174}")
    private String allowedOrigins;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // 공개 경로 — 인증 불필요
                        .pathMatchers("/auth/**", "/actuator/**").permitAll()
                        // 상품·카테고리 조회 — 비회원도 허용 (로그인 없이 상품 탐색 가능)
                        .pathMatchers(HttpMethod.GET, "/api/products/**", "/api/categories/**").permitAll()
                        // 내부 서비스 간 API — 외부에서 완전 차단
                        .pathMatchers("/internal/**").denyAll()
                        // 상품 관리 — 판매자(MEMBER)만
                        .pathMatchers(HttpMethod.POST, "/api/products/**", "/api/categories/**").hasRole("MEMBER")
                        .pathMatchers(HttpMethod.PUT, "/api/products/**").hasRole("MEMBER")
                        .pathMatchers(HttpMethod.DELETE, "/api/products/**").hasRole("MEMBER")
                        // 주문 — 소비자(CUSTOMER)만 생성, 조회는 둘 다
                        .pathMatchers(HttpMethod.POST, "/api/orders/**").hasRole("CUSTOMER")
                        .pathMatchers(HttpMethod.GET, "/api/orders/**").authenticated()
                        // 채팅 — 소비자(CUSTOMER)만
                        .pathMatchers("/api/chat/**").hasRole("CUSTOMER")
                        // 결제 — 소비자(CUSTOMER)만
                        .pathMatchers("/api/payments/**").hasRole("CUSTOMER")
                        // 재고 입고(PATCH) — 판매자(MEMBER)만, 조회(GET)는 인증된 사용자(소비자 포함)
                        .pathMatchers(HttpMethod.PATCH, "/api/inventory/**").hasRole("MEMBER")
                        .pathMatchers(HttpMethod.GET, "/api/inventory/**").authenticated()
                        // 배송 관리 — 판매자(MEMBER)만
                        .pathMatchers("/api/deliveries/**").hasRole("MEMBER")
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(rs -> rs.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                ));

        return http.build();
    }

    /**
     * CORS 설정 — 로컬: 5173(소비자), 5174(판매자) 허용
     * 운영: 실제 도메인으로 교체 필요
     *
     * @Order(HIGHEST_PRECEDENCE): Spring Security(-100)보다 먼저 실행되어야
     * denyAll() 응답에도 CORS 헤더가 붙음
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }

    /**
     * JWT type claim → Spring Security 권한 매핑
     * "MEMBER" → ROLE_MEMBER, "CUSTOMER" → ROLE_CUSTOMER
     */
    @Bean
    public ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String type = jwt.getClaimAsString("type");
            if (type == null || type.isBlank()) return Flux.empty();
            return Flux.just(new SimpleGrantedAuthority("ROLE_" + type));
        });
        return converter;
    }
}