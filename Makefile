.DEFAULT_GOAL := help
SHELL := /bin/bash

# ── Variables ──────────────────────────────────────────────────────────────────
MVN        := ./mvnw
PROFILES   := -Dspring.profiles.active=local

.PHONY: help infra-up infra-down db-migrate build test verify run smoke bench load image deploy-demo

# ── Help ───────────────────────────────────────────────────────────────────────
help: ## Show this help (default target)
	@echo ""
	@echo "  Athena Matching Engine — available targets"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'
	@echo ""

# ── Infrastructure ─────────────────────────────────────────────────────────────
infra-up: ## Start the full local stack (Postgres, Redis, Kafka, observability)
	@echo "→ Starting local infrastructure…"
	docker compose up -d
	@echo "→ Waiting for services to be healthy…"
	docker compose ps

infra-down: ## Stop and remove all local infrastructure containers
	@echo "→ Stopping local infrastructure…"
	docker compose down --remove-orphans

# ── Database ───────────────────────────────────────────────────────────────────
db-migrate: ## Run Flyway migrations against the local database
	@echo "→ Running Flyway migrations…"
	$(MVN) -pl modules/adapter-persistence flyway:migrate $(PROFILES) -q

# ── Build ──────────────────────────────────────────────────────────────────────
build: ## Compile all modules (skip tests)
	@echo "→ Compiling…"
	$(MVN) clean compile -T 1C -q

test: ## Run unit tests (surefire — no containers required)
	@echo "→ Running unit tests…"
	$(MVN) test -T 1C

format: ## Apply Spotless (google-java-format) — requires JDK 21
	@echo "→ Applying Spotless formatting…"
	$(MVN) spotless:apply -q

verify: ## Full verification: compile, unit tests, ArchUnit, JaCoCo (Spotless enforced in CI with JDK 21)
	@echo "→ Running full verification pipeline…"
	$(MVN) verify

verify-it: ## Full verification including integration tests (requires Docker)
	@echo "→ Running full verification with integration tests (requires Docker)…"
	$(MVN) verify -DskipITs=false

# ── Run ────────────────────────────────────────────────────────────────────────
run: ## Start the application locally (requires infra-up first)
	@echo "→ Starting Athena on :8080 (gRPC :9090)…"
	$(MVN) -pl modules/bootstrap spring-boot:run $(PROFILES)

# ── Testing ────────────────────────────────────────────────────────────────────
smoke: ## Run smoke tests against a running local instance
	@echo "→ Running smoke tests…"
	@bash scripts/smoke.sh

bench: ## Run JMH micro-benchmarks (OrderBook throughput + latency)
	@echo "→ Running JMH benchmarks…"
	$(MVN) -pl modules/domain test -Dtest=OrderBookBenchmark -Dsurefire.failIfNoSpecifiedTests=false

load: ## Run Gatling load tests (requires a running instance)
	@echo "→ Running Gatling load tests against localhost:8080…"
	$(MVN) gatling:test -pl modules/bootstrap

# ── Docker image ───────────────────────────────────────────────────────────────
image: ## Build Docker image with JIB (no Dockerfile required)
	@echo "→ Building Docker image via JIB…"
	$(MVN) -pl modules/bootstrap jib:dockerBuild -q

# ── Demo ───────────────────────────────────────────────────────────────────────
deploy-demo: ## Build image and start full demo stack
	@echo "→ Building image and starting demo stack…"
	$(MAKE) image
	$(MAKE) infra-up
	@echo ""
	@echo "  Athena is running:"
	@echo "  REST   → http://localhost:8080/swagger-ui.html"
	@echo "  Health → http://localhost:8080/actuator/health"
	@echo "  Metrics → http://localhost:8080/actuator/prometheus"
	@echo "  Grafana → http://localhost:3000  (admin/admin)"
	@echo ""
