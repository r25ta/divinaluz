# Backup Integral da Conversa - N.A.E. Divina Luz

*Backup gerado em 20 de Setembro de 2026*

Este documento contém o registro completo e detalhado (com todos os códigos-fonte) da criação do MVP para automatização do prontuário de assistência espiritual do Centro Espírita Divina Luz.

---

## 1. Escopo e Modelagem Inicial

**Objetivo:** Automatizar o prontuário físico (fichas azuis) contendo dados cadastrais, controle de presença (1 a 8 vezes/séries), e gatilho de reavaliação a cada 4 sessões. Limite de 1 sessão por semana para o tratamento.

**Stack:** Java 17+, Spring Boot 3.x, PostgreSQL, Thymeleaf, HTML5/Bootstrap 5.

---

## 2. Configurações do Projeto

### `pom.xml` (Dependências Principais)
```xml
<!-- Banco de Dados e ORM -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Web e Front-end -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
```
*(Nota: A dependência `spring-boot-starter-security` foi removida para evitar bloqueios de login durante os testes do MVP).*

### `src/main/resources/application.properties`
```properties
# Configurações do Servidor Web
server.port=8081
server.servlet.context-path=/divinaluz

# Configurações do Banco de Dados PostgreSQL
spring.datasource.url=jdbc:postgresql://localhost:5432/divinaluz
spring.datasource.username=postgres
spring.datasource.password=sua_senha
spring.jpa.hibernate.ddl-auto=none
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
```

---

## 3. Banco de Dados (PostgreSQL)

### Script de Criação (DDL)
```sql
-- Criação da tabela Assistido (Dados Cadastrais)
CREATE TABLE assistido (
    id BIGSERIAL PRIMARY KEY,
    nome VARCHAR(255) NOT NULL,
    residencia VARCHAR(255),
    idade INTEGER,
    estado_civil VARCHAR(50),
    sexo VARCHAR(2),
    email VARCHAR(255),
    vinculo VARCHAR(50) -- Ex: ASSISTIDO, TRABALHADOR, ALUNO
);

-- Criação da tabela Avaliacao (Controle de Vezes/Entrevistas)
CREATE TABLE avaliacao (
    id BIGSERIAL PRIMARY KEY,
    assistido_id BIGINT NOT NULL,
    numero_vez INTEGER NOT NULL, -- Corresponde a 1ª VEZ, 2ª VEZ, etc.
    data DATE,
    entrevistador VARCHAR(255),
    historico TEXT,
    evolucao VARCHAR(20), -- Ex: BOM, INDIFERENTE, PIOR, MELHOR
    
    CONSTRAINT fk_avaliacao_assistido 
        FOREIGN KEY (assistido_id) 
        REFERENCES assistido(id) 
        ON DELETE CASCADE
);

-- Criação da tabela SessaoTratamento (Resultado da Consulta)
CREATE TABLE sessao_tratamento (
    id BIGSERIAL PRIMARY KEY,
    assistido_id BIGINT NOT NULL,
    numero_serie INTEGER NOT NULL, -- Corresponde a 1ª SÉRIE, 2ª SÉRIE, etc.
    data_consulta DATE NOT NULL,
    
    -- Recomendações marcadas no cartão (Booleanos)
    visto BOOLEAN DEFAULT FALSE,
    assistencia BOOLEAN DEFAULT FALSE,
    evangelho_no_lar BOOLEAN DEFAULT FALSE,
    leituras BOOLEAN DEFAULT FALSE,
    escola BOOLEAN DEFAULT FALSE,
    trabalho_espiritual BOOLEAN DEFAULT FALSE,
    medico BOOLEAN DEFAULT FALSE,
    
    observacoes TEXT,
    
    CONSTRAINT fk_sessao_assistido 
        FOREIGN KEY (assistido_id) 
        REFERENCES assistido(id) 
        ON DELETE CASCADE
);

-- Índices
CREATE INDEX idx_avaliacao_assistido ON avaliacao(assistido_id);
CREATE INDEX idx_sessao_assistido ON sessao_tratamento(assistido_id);
CREATE INDEX idx_sessao_data_consulta ON sessao_tratamento(data_consulta);
```

---

## 4. Código Fonte Java (Back-end)

### `src/main/java/com/divinaluz/model/Assistido.java`
```java
package com.divinaluz.model;

import jakarta.persistence.*;

@Entity
public class Assistido {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nome;
    private String residencia;
    private Integer idade;
    private String estadoCivil;
    private String sexo;
    private String email;
    private String vinculo; // ASSISTIDO, TRABALHADOR, ALUNO

    // Getters e Setters
}
```

### `src/main/java/com/divinaluz/model/SessaoTratamento.java`
```java
package com.divinaluz.model;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
public class SessaoTratamento {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne
    @JoinColumn(name = "assistido_id")
    private Assistido assistido;
    
    private Integer numeroSerie; 
    private LocalDate dataConsulta;
    
    private boolean visto;
    private boolean assistencia;
    private boolean evangelhoNoLar;
    private boolean leituras;
    private boolean escola;
    private boolean trabalhoEspiritual;
    private boolean medico;
    
    private String observacoes;

    // Getters e Setters
}
```

### `src/main/java/com/divinaluz/repository/AssistidoRepository.java`
```java
package com.divinaluz.repository;

import com.divinaluz.model.Assistido;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistidoRepository extends JpaRepository<Assistido, Long> {
}
```

### `src/main/java/com/divinaluz/repository/SessaoRepository.java`
```java
package com.divinaluz.repository;

import com.divinaluz.model.SessaoTratamento;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SessaoRepository extends JpaRepository<SessaoTratamento, Long> {
    List<SessaoTratamento> findByAssistidoIdOrderByDataConsultaDesc(Long assistidoId);
    Optional<SessaoTratamento> findFirstByAssistidoIdOrderByDataConsultaDesc(Long assistidoId);
}
```

### `src/main/java/com/divinaluz/service/TratamentoService.java`
```java
package com.divinaluz.service;

import com.divinaluz.model.SessaoTratamento;
import com.divinaluz.repository.SessaoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
public class TratamentoService {

    @Autowired
    private SessaoRepository sessaoRepository;

    public void registrarSessao(SessaoTratamento novaSessao) throws Exception {
        Optional<SessaoTratamento> ultimaSessao = sessaoRepository
                .findFirstByAssistidoIdOrderByDataConsultaDesc(novaSessao.getAssistido().getId());

        if (ultimaSessao.isPresent()) {
            long diasPassados = ChronoUnit.DAYS.between(ultimaSessao.get().getDataConsulta(), novaSessao.getDataConsulta());
            
            // Regra 1: Apenas uma sessão por semana
            if (diasPassados < 7) {
                throw new Exception("O assistido já realizou uma sessão nos últimos 7 dias. Próxima sessão liberada em: " 
                        + ultimaSessao.get().getDataConsulta().plusDays(7));
            }
        }

        long totalSessoes = sessaoRepository.findByAssistidoIdOrderByDataConsultaDesc(novaSessao.getAssistido().getId()).size();
        
        // Regra 2: A cada 4 sessões, exige nova avaliação
        if (totalSessoes > 0 && totalSessoes % 4 == 0) {
            throw new Exception("O assistido completou 4 sessões e precisa passar por uma Nova Avaliação antes de continuar.");
        }

        novaSessao.setNumeroSerie((int) totalSessoes + 1);
        sessaoRepository.save(novaSessao);
    }
}
```

### `src/main/java/com/divinaluz/controller/ProntuarioController.java`
```java
package com.divinaluz.controller;

import com.divinaluz.model.Assistido;
import com.divinaluz.model.SessaoTratamento;
import com.divinaluz.repository.AssistidoRepository;
import com.divinaluz.repository.SessaoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/")
public class ProntuarioController {

    @Autowired
    private AssistidoRepository assistidoRepository;

    @Autowired
    private SessaoRepository sessaoRepository;

    @GetMapping
    public String index(Model model) {
        model.addAttribute("assistidos", assistidoRepository.findAll());
        return "index";
    }

    @GetMapping("/novo")
    public String novo(Model model) {
        model.addAttribute("assistido", new Assistido());
        return "form";
    }

    @PostMapping("/salvar")
    public String salvar(@ModelAttribute Assistido assistido) {
        assistidoRepository.save(assistido);
        return "redirect:/";
    }

    @GetMapping("/prontuario/{id}")
    public String verProntuario(@PathVariable Long id, Model model) {
        Assistido assistido = assistidoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + id));
        
        List<SessaoTratamento> sessoes = sessaoRepository.findByAssistidoIdOrderByDataConsultaDesc(id);

        model.addAttribute("assistido", assistido);
        model.addAttribute("sessoes", sessoes);
        
        return "prontuario"; 
    }
}
```

---

## 5. Front-end (Thymeleaf - `src/main/resources/templates/`)

### `index.html`
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
    <title>Divina Luz - Prontuários</title>
</head>
<body class="container mt-5">
    <h2>Assistidos (Prontuários)</h2>
    <a th:href="@{/novo}" class="btn btn-primary mb-3">Novo Cadastro</a>
    
    <table class="table table-bordered table-striped">
        <thead class="table-dark">
            <tr>
                <th>ID</th>
                <th>Nome</th>
                <th>Vínculo</th>
                <th>Ações</th>
            </tr>
        </thead>
        <tbody>
            <tr th:each="a : ${assistidos}">
                <td th:text="${a.id}"></td>
                <td th:text="${a.nome}"></td>
                <td th:text="${a.vinculo}"></td>
                <td>
                    <a th:href="@{/prontuario/{id}(id=${a.id})}" class="btn btn-sm btn-info text-white">Ver Prontuário Completo</a>
                </td>
            </tr>
        </tbody>
    </table>
</body>
</html>
```

### `form.html`
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title>N.A.E. Divina Luz - Cadastro de Assistido</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
</head>
<body class="bg-light">

<div class="container mt-5">
    <div class="card shadow">
        <div class="card-header bg-info text-white">
            <h3 class="mb-0">Cartão de Registro de Assistência Espiritual</h3>
        </div>
        <div class="card-body">
            
            <form th:action="@{/salvar}" th:object="${assistido}" method="post">
                
                <div class="row mb-3">
                    <div class="col-md-6">
                        <label class="form-label">Nome Completo</label>
                        <input type="text" class="form-control" th:field="*{nome}" required>
                    </div>
                    <div class="col-md-6">
                        <label class="form-label">Residência (Endereço)</label>
                        <input type="text" class="form-control" th:field="*{residencia}">
                    </div>
                </div>

                <div class="row mb-3">
                    <div class="col-md-3">
                        <label class="form-label">Idade</label>
                        <input type="number" class="form-control" th:field="*{idade}">
                    </div>
                    <div class="col-md-3">
                        <label class="form-label">Estado Civil</label>
                        <input type="text" class="form-control" th:field="*{estadoCivil}">
                    </div>
                    <div class="col-md-3">
                        <label class="form-label">Sexo</label>
                        <select class="form-select" th:field="*{sexo}">
                            <option value="">Selecione...</option>
                            <option value="M">Masculino</option>
                            <option value="F">Feminino</option>
                        </select>
                    </div>
                    <div class="col-md-3">
                        <label class="form-label">E-mail</label>
                        <input type="email" class="form-control" th:field="*{email}">
                    </div>
                </div>

                <div class="row mb-4">
                    <div class="col-md-12">
                        <label class="form-label fw-bold">Categoria (Vínculo)</label>
                        <div class="d-flex gap-4">
                            <div class="form-check">
                                <input class="form-check-input" type="radio" th:field="*{vinculo}" value="ASSISTIDO" required>
                                <label class="form-check-label">Assistido</label>
                            </div>
                            <div class="form-check">
                                <input class="form-check-input" type="radio" th:field="*{vinculo}" value="TRABALHADOR">
                                <label class="form-check-label">Trabalhador</label>
                            </div>
                            <div class="form-check">
                                <input class="form-check-input" type="radio" th:field="*{vinculo}" value="ALUNO">
                                <label class="form-check-label">Aluno</label>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="d-flex justify-content-end gap-2">
                    <a th:href="@{/}" class="btn btn-secondary">Cancelar</a>
                    <button type="submit" class="btn btn-primary">Salvar Prontuário</button>
                </div>
            </form>

        </div>
    </div>
</div>

</body>
</html>
```

### `prontuario.html`
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title>Detalhes do Prontuário</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
</head>
<body class="bg-light">

<div class="container mt-5">
    <div class="d-flex justify-content-between align-items-center mb-3">
        <h2>Prontuário de Assistência Espiritual</h2>
        <a th:href="@{/}" class="btn btn-secondary">Voltar para Lista</a>
    </div>

    <!-- Cabeçalho (Frente do Cartão) -->
    <div class="card shadow mb-4">
        <div class="card-header bg-primary text-white">
            <h5 class="mb-0">Dados do Assistido</h5>
        </div>
        <div class="card-body">
            <div class="row">
                <div class="col-md-6"><p><strong>Nome:</strong> <span th:text="${assistido.nome}"></span></p></div>
                <div class="col-md-6"><p><strong>Residência:</strong> <span th:text="${assistido.residencia}"></span></p></div>
                <div class="col-md-3"><p><strong>Idade:</strong> <span th:text="${assistido.idade}"></span></p></div>
                <div class="col-md-3"><p><strong>Estado Civil:</strong> <span th:text="${assistido.estadoCivil}"></span></p></div>
                <div class="col-md-3"><p><strong>Vínculo:</strong> <span th:text="${assistido.vinculo}"></span></p></div>
            </div>
        </div>
    </div>

    <!-- Tabela de Sessões (Verso do Cartão) -->
    <div class="card shadow">
        <div class="card-header bg-success text-white d-flex justify-content-between align-items-center">
            <h5 class="mb-0">Histórico de Tratamento (Sessões)</h5>
            <button class="btn btn-light btn-sm">Registrar Nova Sessão</button>
        </div>
        <div class="card-body p-0">
            <table class="table table-striped table-hover mb-0">
                <thead class="table-dark text-center">
                    <tr>
                        <th>Série</th>
                        <th>Data da Consulta</th>
                        <th>Visto</th>
                        <th>Assistência</th>
                        <th>Evangelho no Lar</th>
                        <th>Leituras</th>
                        <th>Escola</th>
                        <th>Trabalho Espirit.</th>
                        <th>Médico</th>
                    </tr>
                </thead>
                <tbody class="text-center">
                    <tr th:each="sessao : ${sessoes}">
                        <td th:text="${sessao.numeroSerie} + 'ª SÉRIE'"></td>
                        <td th:text="${#temporals.format(sessao.dataConsulta, 'dd/MM/yyyy')}"></td>
                        <td><input type="checkbox" th:checked="${sessao.visto}" disabled></td>
                        <td><input type="checkbox" th:checked="${sessao.assistencia}" disabled></td>
                        <td><input type="checkbox" th:checked="${sessao.evangelhoNoLar}" disabled></td>
                        <td><input type="checkbox" th:checked="${sessao.leituras}" disabled></td>
                        <td><input type="checkbox" th:checked="${sessao.escola}" disabled></td>
                        <td><input type="checkbox" th:checked="${sessao.trabalhoEspiritual}" disabled></td>
                        <td><input type="checkbox" th:checked="${sessao.medico}" disabled></td>
                    </tr>
                    <tr th:if="${#lists.isEmpty(sessoes)}">
                        <td colspan="9" class="text-muted py-3">Nenhuma sessão registrada para este assistido ainda.</td>
                    </tr>
                </tbody>
            </table>
        </div>
    </div>
</div>
</body>
</html>
```