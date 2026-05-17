# ADR-001: Arquitetura Hexagonal (Ports & Adapters)

**Status:** Accepted

**Date:** 2026-05-17

---

## Contexto

Um matching engine precisa suportar múltiplos protocolos de entrada (REST, gRPC, WebSocket, FIX no futuro) e múltiplos destinos de saída (Postgres, Kafka, Redis) sem que a lógica de negócio conheça esses detalhes. A abordagem clássica em camadas (Controller → Service → Repository) tende a vazar dependências de infraestrutura para o domínio ao longo do tempo, especialmente quando JPA annotations aparecem em entidades de domínio.

O projeto também precisa ser facilmente testável sem infraestrutura: testar o book de ordens não deve exigir Kafka ou Postgres rodando.

## Decisão

Adotamos Hexagonal Architecture (Ports & Adapters, Alistair Cockburn, 2005):

- O módulo `domain` é Java puro. Zero frameworks. ArchUnit quebra a build se qualquer dependência de framework aparecer.
- O módulo `application` contém casos de uso, portas de entrada (`port/inbound`) e portas de saída (`port/outbound`), e serviços de orquestração. Conhece apenas `domain`.
- Módulos `adapter-*` implementam as portas: recebem do mundo externo e delegam para a aplicação, ou recebem da aplicação e enviam para o mundo externo.
- O módulo `bootstrap` monta tudo via Spring Boot, sendo o único lugar que conhece todos os módulos.

A estrutura de pacotes dentro de cada módulo segue: `domain/`, `application/(command, query, port/inbound, port/outbound, service)`, `adapter/(rest, grpc, ws, persistence, messaging)`.

## Consequências

### Positivas
- Domínio e casos de uso 100% testáveis em memória, sem containers.
- Troca de banco, broker ou protocolo não afeta o domínio.
- Regras de dependência são enforçadas automaticamente pelo ArchUnit, não por disciplina manual.
- Onboarding mais previsível: cada módulo tem responsabilidade clara.

### Negativas / trade-offs
- Mais boilerplate na borda: objetos de domínio precisam ser mapeados para/de DTOs nos adapters.
- Para operações simples (ex: listar trades), a cadeia domain ← application ← adapter parece excessiva. Aceitamos esse custo porque a consistência vale mais que a conveniência em sistemas financeiros.

### Neutras / a observar
- O mapeamento adapter ↔ application pode ser automatizado com records e factory methods sem Lombok.

## Alternativas consideradas

| Alternativa | Por que descartada |
|-------------|-------------------|
| Camadas clássicas (MVC) | Vaza infraestrutura para o domínio com o tempo, especialmente JPA annotations |
| Clean Architecture (Uncle Bob) | Essencialmente a mesma coisa com nomenclatura diferente; preferimos o termo original de Cockburn |
| DDD sem ports & adapters | Sem isolamento garantido; dependências de infra entram sub-repticiamente |

## Referências

- Alistair Cockburn, [Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/) (2005)
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2019)
