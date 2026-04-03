package allmart.apigatewayservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * JWT 검증 후 사용자 정보를 downstream 서비스에 헤더로 전달
 *
 * Spring Security가 JWT를 검증한 후 SecurityContext에 JwtAuthenticationToken을 저장.
 * 이 필터는 그 토큰에서 subject(식별자)와 type(MEMBER|CUSTOMER)을 추출해 헤더로 추가.
 *
 * X-User-Subject: admin@mart.com  (판매자) or "12345678" (카카오 고객)
 * X-User-Type:    MEMBER | CUSTOMER
 * X-User-Id:      customerId (Long) — CUSTOMER만 존재, order-service가 buyerId로 사용
 */
@Slf4j
@Component
public class UserContextFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .flatMap(principal -> {
                    if (principal instanceof JwtAuthenticationToken jwtAuth) {
                        var jwt = jwtAuth.getToken();
                        String type = jwt.getClaimAsString("type");
                        String path = exchange.getRequest().getPath().value();
                        var requestMutate = exchange.getRequest().mutate()
                                .header("X-User-Subject", jwt.getSubject())
                                .header("X-User-Type", type != null ? type : "");

                        // uid claim: CUSTOMER JWT에 포함된 customerId (DB PK)
                        Number uid = jwt.getClaim("uid");
                        if (uid != null) {
                            requestMutate.header("X-User-Id", String.valueOf(uid.longValue()));
                        }

                        if (type == null) {
                            log.warn("JWT type claim 없음: subject={}, path={}", jwt.getSubject(), path);
                        } else {
                            log.debug("JWT 컨텍스트 주입: subject={}, type={}, uid={}, path={}",
                                    jwt.getSubject(), type, uid, path);
                        }

                        ServerHttpRequest mutated = requestMutate.build();
                        return chain.filter(exchange.mutate().request(mutated).build());
                    }
                    return chain.filter(exchange);
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        return -1; // Security 필터 이후, 라우팅 이전
    }
}