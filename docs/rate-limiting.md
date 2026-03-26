# Spring Cloud Gateway — Rate Limiting 학습 문서

## 실측 결과 (2026-03-26)

**테스트 조건**: k6 rate-limit.js, 20 VU × 5s, replenishRate=5, burstCapacity=10

| 지표 | 직접 호출 (:8080) | Gateway 경유 (:8000) |
|------|------------------|----------------------|
| 총 요청 수 | 365건 | 10,887건 |
| pay-service 도달 | 365건 (100%) | **35건 (0.33%)** |
| 429 차단 | 0건 | **10,852건 (99.67%)** |
| 평균 응답시간 | 279ms | **8.92ms** |
| P95 응답시간 | 1.21s | 12.81ms |

**핵심**: 이론값 `burstCapacity(10) + replenishRate(5) × 5초 = 35건`과 실측 35건 정확히 일치.
Gateway가 없으면 10,887건 전부 pay-service에 도달한다.

---

## 왜 필요한가

결제 확인 API(`POST /api/payments/toss/confirm`)에 rate limit이 없으면:

```
악의적 클라이언트가 초당 1000번 호출
  → Toss API 호출 1000번 → 비용 발생
  → DB insert 1000번 → 부하 증가
  → 합법적 사용자 응답 지연
```

API Gateway에서 IP 기준으로 초당 5건으로 제한하면
진입 전에 차단 → downstream 서비스 보호

---

## 토큰 버킷 알고리즘

```
버킷: [●●●●●●●●●●]  ← burstCapacity = 10
        초당 +5 보충 ← replenishRate = 5

요청 1건 처리 → 토큰 1개 소비
버킷 = [●●●●●●●●●] (9개)

...10건 빠르게 처리 → 버킷 비워짐
버킷 = []  → 다음 요청 → 429 Too Many Requests

1초 후 → 토큰 5개 보충
버킷 = [●●●●●]  → 다시 처리 가능
```

| 설정 | 의미 | 선택 기준 |
|------|------|-----------|
| `replenishRate: 5` | 초당 허용 요청 수 (정상 처리량) | 일반 사용자 패턴 기준 |
| `burstCapacity: 10` | 순간 허용 최대치 (버스트) | 재시도 허용 여유분 |
| `requestedTokens: 1` | 요청당 소비 토큰 | 무거운 작업은 2~3으로 설정 가능 |

---

## 왜 토큰 버킷인가 — 알고리즘 선택 이유

Rate Limiting 알고리즘은 3가지가 대표적이다.

### Fixed Window (고정 창)

```
[0s ~ 1s] 허용 5건   [1s ~ 2s] 허용 5건
         ↑                    ↑
         리셋                 리셋
```

**문제**: 창 경계에서 버스트 가능.
0.9초에 5건 + 1.1초에 5건 = 실질적으로 0.2초 안에 10건 통과.

### Sliding Window (슬라이딩 창)

```
지금 기준 최근 1초 내 요청 수를 카운트
```

정확하지만 Redis에 요청 타임스탬프를 모두 저장해야 함 → 메모리 사용 높음.

### Token Bucket (토큰 버킷) ← 선택

```
버킷에 토큰을 채우고, 요청마다 소비
초당 N개 보충, 최대 M개까지 쌓임
```

**선택 이유 3가지:**

1. **버스트 허용**: 사용자가 잠깐 쉬었다가 연속 요청하면 쌓인 토큰으로 허용.
   결제 API는 실제 사용자가 한 번에 몇 건 빠르게 시도할 수 있음.
   Fixed Window처럼 갑자기 막히면 UX가 나쁨.

2. **구현이 단순하고 Redis 친화적**: 토큰 수(`tokens`) + 타임스탬프(`timestamp`) 2개 키만 필요.
   Spring Cloud Gateway의 `RedisRateLimiter`가 이 방식을 Lua 스크립트로 원자적 실행.

3. **초과 즉시 차단**: 버킷이 비면 다음 요청은 즉시 429. 응답 지연 없이 빠른 거절.

---

## 왜 Gateway에서 하는가 — 서비스 레벨과의 차이

```
클라이언트
    ↓
[Gateway] ← rate limit 여기서
    ↓ (허용된 요청만)
[pay-service]
    ↓
[Redis 분산 락]  ← 중복 결제 방지는 여기서
    ↓
[Toss API]
```

**pay-service 내부에서 rate limit을 구현하면:**
- 이미 Tomcat 스레드를 점유한 후 거절 → 자원 낭비
- pay-service 인스턴스가 여러 개면 인스턴스별로 카운트가 분산되어 실제 제한 효과가 줄어듦

**Gateway에서 하면:**
- pay-service 도달 전에 차단 → downstream 완전 보호
- 인스턴스 수와 무관하게 전체 트래픽 기준으로 제한
- 결제 외 다른 API에도 동일 패턴 재사용 가능

---

## 왜 replenishRate=5, burstCapacity=10인가

```
실제 사용자 결제 패턴:
  - 결제 1건: confirm API 1번 호출
  - 실패 시 재시도: 1~2번 추가
  - 합리적 최대: 초당 3~4건

replenishRate=5  → 초당 5건 허용 (정상 사용 여유 포함)
burstCapacity=10 → 최대 누적 10건 (잠깐 쉬었다가 몰아서 해도 허용)
```

악의적 호출 패턴(초당 수백 건)은 버킷이 즉시 소진되어 차단된다.

---

## 구성 변경

### build.gradle.kts

```kotlin
// WebFlux 기반 Gateway → reactive Redis 필수
implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")

// 일반 spring-boot-starter-data-redis는 Netty reactor와 충돌 발생
```

**왜 reactive Redis인가?**
Gateway는 Spring WebFlux(Netty) 기반. 블로킹 Redis 클라이언트(`Jedis`)를 쓰면 Netty 이벤트 루프 스레드를 블로킹해 처리량이 급격히 저하된다.
`spring-boot-starter-data-redis-reactive`는 Lettuce(비동기) 클라이언트를 사용한다.

### application.yml — Route 설정

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: payment-confirm
              uri: lb://pay-service          # Eureka 서비스 디스커버리
              predicates:
                - Path=/api/payments/**
              filters:
                - name: RequestRateLimiter
                  args:
                    redis-rate-limiter.replenishRate: 5
                    redis-rate-limiter.burstCapacity: 10
                    redis-rate-limiter.requestedTokens: 1
                    key-resolver: "#{@ipKeyResolver}"  # SpEL Bean 참조
```

`key-resolver`의 `#{@beanName}` 형식은 Spring Expression Language로 ApplicationContext에서 Bean을 주입한다.

### KeyResolver Bean

```java
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
            exchange.getRequest().getRemoteAddress()
                    .getAddress().getHostAddress()
        );
    }
}
```

`KeyResolver`는 `Mono<String>`을 반환 — WebFlux 비동기 파이프라인.
각 요청에서 rate limit 키를 동적으로 결정한다.

**다른 KeyResolver 예시:**
```java
// Authorization 헤더의 사용자 ID 기준
return exchange -> Mono.just(
    exchange.getRequest().getHeaders().getFirst("X-User-Id")
);

// API Key 기준
return exchange -> Mono.just(
    exchange.getRequest().getQueryParams().getFirst("apiKey")
);
```

---

## Redis 키 구조

Rate Limiter가 Redis에 저장하는 키:

```
request_rate_limiter.{key}.tokens    → 현재 남은 토큰 수
request_rate_limiter.{key}.timestamp → 마지막 토큰 보충 시간
```

예) IP 127.0.0.1의 경우:
```
request_rate_limiter.127.0.0.1.tokens    → 7
request_rate_limiter.127.0.0.1.timestamp → 1711420800
```

Redis CLI로 실시간 확인 가능:
```bash
docker exec -it <redis-container> redis-cli
KEYS request_rate_limiter.*
GET request_rate_limiter.127.0.0.1.tokens
```

---

## 로컬 vs 프로덕션 설정 분리

### application-local.yml

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: payment-confirm
              uri: http://localhost:8080  # 직접 URI (Eureka 없음)
              ...

eureka:
  client:
    register-with-eureka: false  # Eureka 서버 없으므로 비활성화
    fetch-registry: false
```

### application.yml (프로덕션 기준)

```yaml
- uri: lb://pay-service  # Eureka 기반 로드밸런싱
```

`lb://` = Load Balancer. Eureka에서 `pay-service` 인스턴스 목록을 조회해 라운드로빈으로 라우팅한다.

---

## 실행 순서

```bash
# 1. Redis 기동 (pay-service compose)
docker compose up -d redis

# 2. pay-service 기동
./gradlew bootRun --args='--spring.profiles.active=local'

# 3. apigateway-service 기동 (별도 터미널)
./gradlew bootRun --args='--spring.profiles.active=local'

# 4. k6 테스트 실행
k6 run k6\rate-limit.js
```

---

## 응답 헤더 확인

Rate Limiter가 동작하면 응답 헤더에 남은 토큰 정보가 포함된다:

```http
X-RateLimit-Remaining: 4
X-RateLimit-Burst-Capacity: 10
X-RateLimit-Replenish-Rate: 5
X-RateLimit-Requested-Tokens: 1
```

429 응답 시:
```http
HTTP/1.1 429 Too Many Requests
X-RateLimit-Remaining: 0
```

---

## 한계 및 발전 방향

| 현재 구현 | 한계 | 발전 방향 |
|-----------|------|-----------|
| IP 기반 키 | LB 뒤에서는 모든 요청이 같은 IP | X-Forwarded-For 헤더 사용 |
| 전역 rate limit | 인증된 사용자와 익명 사용자 구분 없음 | JWT claims 기반 키 |
| 단순 429 반환 | 재시도 시점 알 수 없음 | `Retry-After` 헤더 추가 |
| 단일 Redis | Redis 장애 시 rate limit 우회 가능 | Redis Cluster + Fallback 설정 |