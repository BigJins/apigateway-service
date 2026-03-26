package allmart.apigatewayservice;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    /**
     * Rate limiting 키: 클라이언트 IP 주소
     *
     * 각 IP마다 독립적인 토큰 버킷이 생성된다.
     * → IP A가 rate limit에 걸려도 IP B는 영향 없음
     *
     * 운영 환경에서는 X-Forwarded-For 헤더로 실제 클라이언트 IP를 추출해야 함.
     * (Load Balancer / Proxy 앞에 있을 경우 remoteAddress가 LB IP가 됨)
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
        );
    }
}