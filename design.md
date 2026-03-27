# System Design Document: OmniCart (Real-Time E-Commerce Arbitrage)
**Version:** 1.1 | **Author:** Himanshu Gupta | **Target Level:** SDE 2

## 1. Product Vision & Non-Functional Requirements (NFRs)
OmniCart is a distributed, event-driven arbitrage engine that normalizes fragmented e-commerce product data to provide real-time price comparisons. 

* **Availability:** 99.9% uptime for the API Gateway.
* **Latency:** < 200ms for Cache Hits; < 5 seconds for P99 Live Scrapes.
* **Throughput:** Designed to handle 1,000 concurrent search requests (burst traffic).
* **Consistency:** Eventual consistency across search workers, heavily relying on Redis for real-time state aggregation.

---

## 2. High-Level Architecture (HLD)

The system relies on a fan-out architecture. A single search request is broadcasted to multiple platform-specific workers via Kafka, allowing parallel execution without blocking the main OS threads.

### 2.1. System Architecture Diagram
```mermaid
flowchart TD
    Client[Client / UI / Telegram Bot] -->|POST /api/compare| Gateway(API Gateway - Spring Boot)
    Gateway <-->|Check Price Cache| Redis[(Redis Cluster)]
    Gateway -->|Publish 'SearchEvent'| Kafka[Apache Kafka]
    
    subgraph Parallel Platform Workers
        Kafka -->|Consume| WorkerAmz[Amazon Worker]
        Kafka -->|Consume| WorkerFlip[Flipkart Worker]
        Kafka -->|Consume| WorkerBlink[Blinkit Worker]
    end

    WorkerAmz -->|Scrape| ProxyPool[Proxy Rotation Pool]
    ProxyPool --> WebAmz((Amazon.in))
    
    WorkerFlip --> WebFlip((Flipkart.com))
    
    WorkerAmz -->|Raw HTML| Normalizer(LLM Normalizer / LangChain)
    WorkerFlip -->|Raw HTML| Normalizer
    WorkerBlink -->|Raw HTML| Normalizer

    Normalizer <-->|Vector Match| ChromaDB[(ChromaDB - Vectors)]
    Normalizer -->|Publish 'ResultEvent'| Kafka
    
    Kafka -->|Consume Aggregated| Gateway
    Gateway -->|Save Analytics| MongoDB[(MongoDB)]
    Gateway -->|Return WebSocket / SSE| Client
