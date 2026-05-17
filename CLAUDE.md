# CLAUDE.md — Manual Operacional para Sessões Futuras

Este documento é lido pelo Claude Code no início de cada sessão. Ele define identidade, princípios, workflow e convenções do projeto Athena.

## Identidade do projeto

Athena é um matching engine de ordens (estilo bolsa) em Java 21 + Spring Boot 3.3. É um projeto de portfólio de nível técnico alto — as decisões precisam ser justificadas, o código testado e a performance medida. Nada de scaffolding genérico.

## Antes de começar qualquer sessão

1. Leia os ADRs em `docs/adr/` para entender as decisões já tomadas
2. Verifique o estado do build: `make verify`
3. Leia o ARCHITECTURE.md para lembrar da estrutura hexagonal
4. Nunca assuma que algo não está quebrado — verifique

## Princípios não-negociáveis

1. **Domínio puro:** o módulo `domain` só importa `java.*`. Qualquer framework viola o ArchUnit e quebra o build.
2. **Sem double/float em valores monetários.** `long` em ticks/lots no domínio, `BigDecimal` apenas na borda. Ver ADR-006.
3. **Sem Lombok.** Records e sealed interfaces cobrem tudo.
4. **Sem @Autowired em campo.** Sempre construtor.
5. **Sem Optional como parâmetro de método.**
6. **Sem catch(Exception e) sem rethrow ou tratamento explícito.**
7. **Sem lógica de negócio em controller, listener ou interceptor.**
8. **Sem @Transactional em método de domínio.**
9. **Sem Thread.sleep em testes.** Use Awaitility.
10. **Sem log com concatenação de string.** Use structured logging com key-value pairs.
11. **Idempotência ponta-a-ponta:** toda operação de escrita aceita Idempotency-Key.
12. **Observabilidade obrigatória:** toda operação pública emite métrica (Micrometer), trace (OTel) e log estruturado JSON.
13. **Testes com Testcontainers reais.** Nunca H2 ou embedded.
14. **Sem segredo em código.** Variáveis de ambiente com defaults em application.yml, documentadas em .env.example.

## Anti-padrões a recusar mesmo se o usuário pedir

- Adicionar Spring no módulo domain
- Usar double/float em valor monetário
- @Autowired em campo
- Optional como parâmetro
- catch (Exception e) sem tratamento
- Lógica de negócio em controller
- @Transactional em domínio
- Thread.sleep em teste
- Logs com concatenação
- H2/HSQLDB/embedded DB em teste
- ModelMapper, Guava, Apache Commons Lang (não estão na stack)
- WebFlux/RxJava/Reactor (não estão na stack)

## Workflow por sessão

1. **Ler** ADRs e ARCHITECTURE antes de qualquer alteração
2. **TDD**: escrever o teste antes (ou junto) do código
3. **`make verify`** antes de cada commit — se falhar, corrigir antes de commitar
4. **Commit ao fim de cada bloco lógico** com Conventional Commits em inglês
5. **Nunca commitar segredos** — verificar .gitignore e .env.example

## Comandos úteis

```bash
make help          # lista todos os targets
make infra-up      # sobe Postgres, Redis, Kafka, observabilidade
make infra-down    # derruba infraestrutura
make db-migrate    # roda Flyway
make build         # compila (sem testes)
make test          # testes unitários
make verify        # verificação completa (Spotless + tests + ArchUnit + JaCoCo)
make run           # sobe a aplicação (requer infra-up)
make smoke         # smoke tests (Sprint 3+)
make image         # build Docker com JIB
./mvnw pitest:mutationCoverage -pl modules/domain  # mutation testing
```

## Convenções de código

- Pacote raiz: `com.athena.{trading|marketdata|account}`
- Estrutura interna: ver CONTRIBUTING.md (hexagonal)
- Testes: `should_<behavior>_when_<condition>`, padrão AAA
- Records para value objects, sealed interfaces para hierarquias fechadas
- `long` em ticks/lots, `BigDecimal` apenas em adapters

## Stack (versões fixadas no pom.xml raiz)

Java 21, Spring Boot 3.3.5, LMAX Disruptor 4.0.0, Kafka 3.7, PostgreSQL 16, Redis 7, gRPC 1.65, Testcontainers 1.20, ArchUnit 1.3, JaCoCo 0.8.12.

Stack **recusada**: Lombok, JPA/Hibernate, ModelMapper, H2, WebFlux, RxJava, Quarkus, Micronaut.

## Quando criar um novo ADR

- Nova dependência significativa
- Decisão de arquitetura com alternativas fortes
- Qualquer mudança que quebre uma convenção estabelecida
- Copiar `docs/adr/_TEMPLATE.md` e preencher todos os campos

## Ordem de carregamento recomendada

1. Este arquivo (CLAUDE.md)
2. ARCHITECTURE.md
3. ADRs relevantes ao contexto da sessão
4. CONTRIBUTING.md (para convenções de código)
5. Código atual do módulo em foco
