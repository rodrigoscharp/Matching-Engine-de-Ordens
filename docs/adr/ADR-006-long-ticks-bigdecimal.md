# ADR-006: `long` em ticks/lots no core, `BigDecimal` apenas na borda

**Status:** Accepted

**Date:** 2026-05-17

---

## Contexto

Representação de valores monetários e quantidades é um problema clássico e crítico em sistemas financeiros. Três abordagens comuns:

1. `double`/`float` — PROIBIDO. Representação em ponto flutuante IEEE 754 acumula erros de arredondamento que causam divergências em reconciliações. `0.1 + 0.2 != 0.3` em Java.
2. `BigDecimal` — Correto mas lento. Operações imutáveis, alocação intensiva, GC pressure no hot path.
3. `long` em unidades inteiras (ticks/lots) — Correto e rápido. Aritmética inteira nativa, zero alocação, zero risco de floating-point error.

O matching engine realiza comparações de preço bilhões de vezes. A escolha de representação de preço afeta diretamente o throughput e a correção do sistema.

## Decisão

No módulo `domain` e em todo o hot path de matching:

- **Preços** são representados como `long` em **ticks** (unidade mínima de variação de preço por instrumento).
- **Quantidades** são representadas como `long` em **lots** (unidade mínima negociável por instrumento).
- Cada instrumento define seu `tickSize` e `lotSize` como `long` (ex: PETR4 → tickSize=1 centavo = 1, lotSize=100 ações).

Na borda (adapters REST, gRPC, Kafka):

- `BigDecimal` é usado para serialização/deserialização de entrada e saída (JSON, Protobuf, Avro).
- A conversão tick ↔ BigDecimal acontece **somente** no adapter, nunca no domínio.
- Regra: `price_in_ticks = price_in_brl.multiply(TICKS_PER_BRL).longValueExact()`. O `longValueExact()` falha explicitamente se houver perda de precisão.

Nomes explícitos para evitar confusão:
```java
record Price(long ticks) { ... }         // no domínio
record Quantity(long lots) { ... }       // no domínio
// vs.
BigDecimal priceInBrl = ...;             // no adapter, representação humana
```

## Consequências

### Positivas
- Aritmética inteira no hot path: comparações de preço são `long` comparisons — nanosegundos.
- Zero risco de floating-point error no matching. Dois preços iguais são sempre iguais.
- Serialização explícita na borda: se a conversão falhar (ex: preço com mais casas decimais que o tick size permite), falha ruidosamente, não silenciosamente.
- Compatível com FIX Protocol (que também usa inteiros).

### Negativas / trade-offs
- Desenvolvedores precisam entender a convenção tick/lot para cada instrumento. Documentado no CONTRIBUTING.
- Instrumentos com tick sizes fracionais (ex: câmbio USD/BRL com 4 casas decimais) requerem tick size adequado na configuração do instrumento.
- BigDecimal na borda adiciona overhead de serialização — aceitável pois não é o hot path.

### Neutras / a observar
- Overflow de `long` para preços extremamente altos ou quantidades muito grandes. Monitorar com `Math.addExact` / `Math.multiplyExact` onde relevante.
- A representação interna é um detalhe de implementação — a API pública expõe sempre `BigDecimal`.

## Alternativas consideradas

| Alternativa | Por que descartada |
|-------------|-------------------|
| `double`/`float` | Absolutamente proibido: erros de ponto flutuante em sistema financeiro são inaceitáveis |
| `BigDecimal` no hot path | Correto mas 10-50x mais lento que long; GC pressure inaceitável em > 100k ordens/s |
| Fixed-point library externa (ex: Decimal4j) | Dependência adicional sem ganho real sobre long com convenção bem documentada |
| `int` em vez de `long` | Overflow para instrumentos de alto valor (ex: BTC) |

## Referências

- Martin Fowler, *Money Pattern* — [eaaCatalog](https://martinfowler.com/eaaCatalog/money.html)
- IEEE 754 Double Precision: [Why 0.1 + 0.2 ≠ 0.3](https://0.30000000000000004.com/)
- LMAX Disruptor: tick-based price representation (mesmo padrão)
