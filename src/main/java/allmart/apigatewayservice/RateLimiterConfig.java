package allmart.apigatewayservice;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    /**
     * 로컬 개발 전용: Sentinel 대신 localhost:6379 직접 연결 (reactive)
     *
     * 문제: Docker Sentinel이 master 주소로 컨테이너 내부 IP(172.x.x.x)를 반환하는데
     *       호스트에서 실행 중인 Gateway가 해당 IP에 접근 불가 (Docker Desktop 네트워크 제약)
     * 해결: local 프로파일에서 ReactiveRedisConnectionFactory를 직접 등록하여
     *       auto-configuration의 Sentinel 설정을 우회
     */
    @Profile("local")
    @Primary
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
    }

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