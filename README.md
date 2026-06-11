# apigateway-service

**allmart** 이커머스 플랫폼의 API Gateway 서비스입니다.
Spring Cloud Gateway 기반으로 **RS256 JWT 로컬 검증**, Rate Limiting, 라우팅을 담당합니다.

## JWT 로컬 검증 (JWKS)

```
auth-service의 JWKS 공개키를 캐시 → 요청마다 Gateway에서 로컬 서명 검증
(auth-service 왕복 없음 — 인증 서버가 검증 병목/단일 장애점이 되지 않음)
```

- type claim → `ROLE_MEMBER` / `ROLE_CUSTOMER` 매핑, 경로별 인가
- uid claim → `X-User-Id` 헤더 주입 — downstream은 헤더만 신뢰, 클라이언트 위조 불가
- `/internal/**` 외부 직접 호출 차단 (denyAll)

## 관련 서비스

| 서비스 | 역할 | GitHub |
|--------|------|--------|
| **order-service** | 주문 생성 / 상태 관리 | [BigJins/order-service](https://github.com/BigJins/order-service) |
| **pay-service** | 결제 승인 / Toss Payments 연동 | [BigJins/pay-service](https://github.com/BigJins/pay-service) |
| **apigateway-service** | Rate Limiting / 라우팅 | 현재 레포 |

## 기술 스택

- **Java 21** + Spring Boot 4.0.2
- **Spring Cloud Gateway** — WebFlux 기반 리액티브 게이트웨이
- **Redis** — 토큰 버킷 Rate Limiting (reactive)

## 주요 구현 포인트

### Rate Limiting — 결제 API 보호
Redis 토큰 버킷 알고리즘으로 IP 기준 요청 수 제한.

```yaml
filters:
  - name: RequestRateLimiter
    args:
      redis-rate-limiter.replenishRate: 5    # 초당 5건 보충
      redis-rate-limiter.burstCapacity: 10   # 순간 최대 10건
      key-resolver: "#{@ipKeyResolver}"
```

**Gateway 레벨에서 차단** → downstream 서비스 완전 보호 (Toss API 비용 폭증 방지).

### k6 부하 테스트 결과 (rate-limit.js)

| 지표 | 직접 호출 | Gateway 경유 |
|------|-----------|-------------|
| downstream 도달 | **100%** (365건) | **0.33%** (35건) ✅ |
| Gateway 차단 (429) | 0건 | 10,852건 |
| 평균 응답시간 | 279ms | **8.92ms** (429 즉시 반환) |

> 이론값 `burstCapacity(10) + replenishRate(5) × 5초 = 35건`과 실측 35건 정확히 일치

## 포트

| 서비스 | 포트 |
|--------|------|
| apigateway-service | 8000 |
| pay-service (downstream) | 8080 |
| order-service (downstream) | 8081 |