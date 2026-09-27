# Versão de demonstração na nuvem (gratuita)

A demo usa dois serviços gratuitos, sem cartão de crédito:

- **Neon**: guarda o banco de dados (PostgreSQL).
- **Render**: roda o sistema e gera o link da demo.

Você vai copiar **um endereço** do Neon e colar no Render, junto com **uma senha** que você escolhe. O resto já está configurado no repositório. Dá para fazer tudo pelo navegador do celular, em uns 20 minutos.

> Nomes de botões mudam de vez em quando nesses sites. Se algum não bater exatamente, procure o equivalente com o mesmo sentido.

---

## Parte 1 — Neon (o banco de dados)

### 1.1 Criar a conta
1. Abra **https://neon.tech** e toque em **Sign up**.
2. Escolha **Continue with GitHub** e autorize com a sua conta do GitHub.

### 1.2 Criar o projeto
Logo depois do cadastro o Neon abre a tela **Create project** (se não abrir, toque em **New project**). Preencha:

| Campo | O que colocar |
|---|---|
| **Project name** | `divinaluz` |
| **Postgres version** | deixe a que vier (16 ou mais nova) |
| **Cloud provider** | **AWS** |
| **Region** | **AWS South America East 1 (São Paulo)**. Se não aparecer, use **AWS US East 1** |
| **Database name** (se pedir) | deixe `neondb` |

Toque em **Create project**.

### 1.3 Copiar o endereço de conexão
1. No painel do projeto, toque no botão **Connect** (no topo ou no card "Connection").
2. Na janela que abre:
   - **Branch**: `production` (ou `main`, é o que vier).
   - **Database**: `neondb`.
   - **Connection pooling**: **desligue**. O endereço sem `-pooler` é o certo para esta aplicação.
3. Toque em **Show password**, para a senha aparecer no endereço, e depois em **Copy snippet** (ou no ícone de copiar).

O endereço copiado tem este formato. Guarde-o, porque vai para o Render:

```
postgresql://neondb_owner:npg_XXXXXXXX@ep-alguma-coisa-123456.sa-east-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require
```

Confira três coisas:
- começa com `postgresql://`;
- não tem `****` no lugar da senha (se tiver, faltou tocar em **Show password**);
- não tem `-pooler` no meio (se tiver, desligue o **Connection pooling** e copie de novo).

Não precisa apagar nem trocar nada: pode colar do jeito que veio, com `?sslmode=require&channel_binding=require` e tudo.

---

## Parte 2 — Render (o sistema)

### 2.1 Criar a conta e ligar ao GitHub
1. Abra **https://render.com** e toque em **Get Started** (ou **Sign up**).
2. Escolha **GitHub** e autorize.
3. Quando o GitHub perguntar a quais repositórios o Render pode ter acesso, escolha **Only select repositories**, marque **`r25ta/divinaluz`** e toque em **Install** (ou **Save**).

### 2.2 Criar o serviço a partir do Blueprint
O repositório tem um arquivo `render.yaml` que já descreve tudo: servidor gratuito, Docker e o endereço de verificação.

1. No painel do Render, toque em **+ New** e escolha **Blueprint**.
2. Em **Connect a repository**, escolha **`r25ta/divinaluz`** e toque em **Connect**.
3. Preencha a tela seguinte:

| Campo | O que colocar |
|---|---|
| **Blueprint Name** | `divinaluz-demo` |
| **Branch** | **`claude/retomar-divinaluz-5i25l0`**. Importante: o `main` ainda não tem os arquivos da demo |
| **Blueprint Path** | deixe `render.yaml` |

4. O Render mostra o serviço **divinaluz-demo** que vai criar e pede **duas variáveis**:

| Variável | O que colar |
|---|---|
| **`DATABASE_URL`** | o endereço inteiro que você copiou do Neon, começando com `postgresql://` |
| **`DEMO_ADMIN_SENHA`** | uma senha que você inventa para entrar como `admin` na demo, por exemplo `DivinaLuz-Demo-2026`. Anote: ela também vale para o login de exemplo `assistido` |

5. Toque em **Deploy Blueprint** (ou **Apply**).

### 2.3 Acompanhar o primeiro deploy
1. Toque no serviço **divinaluz-demo** e abra a aba **Logs** (ou **Events**).
2. O primeiro build leva de **5 a 10 minutos**: o Render baixa as bibliotecas e compila o sistema.
3. Está pronto quando aparecerem estas linhas no log:
   ```
   Started DivinaluzApplication in ... seconds
   Demo: dados fictícios cadastrados.
   ```
   E, no topo da página do serviço, o status fica **Live** (verde).
4. O link da demo aparece no topo, no formato **`https://divinaluz-demo.onrender.com`** (pode ter letras a mais no final, se o nome já existir). Esse é o link da apresentação.

---

## Parte 3 — Entrar na demo

| Login | Senha | O que vê |
|---|---|---|
| `admin` | a que você colocou em `DEMO_ADMIN_SENHA` | Tudo (Administrador) |
| `assistido` | a mesma | Só o próprio cartão (Maria Aparecida Souza) e a escala de preleções |

A senha provisória `admin` / `password` do ambiente de desenvolvimento **não** funciona na demo.

---

## Se algo der errado

| O que aparece | O que fazer |
|---|---|
| Log com `Perfil demo sem senha do admin` | Falta a variável `DEMO_ADMIN_SENHA`. No serviço, abra **Environment**, adicione a variável e toque em **Save Changes**; o Render reinicia sozinho |
| Log com `DATABASE_URL precisa começar com postgresql://` ou `sem usuário e senha` | O endereço foi colado incompleto. Copie de novo no Neon (com **Show password**) e cole em **Environment → DATABASE_URL** |
| Log com `password authentication failed` | A senha no endereço está errada ou com `****`. Copie de novo no Neon com **Show password** |
| Log com `Connection refused` ou `timeout` | Confira se o endereço é o do projeto certo e se o banco no Neon está ativo (no painel do Neon, abrir o projeto já acorda o banco) |
| O build falha antes de aparecer `Started` | Copie as últimas linhas do log e me mande |
| O link abre mas demora quase 1 minuto | Normal no plano gratuito: a aplicação "dorme" depois de 15 minutos sem uso e acorda no primeiro acesso |

---

## Antes e durante a apresentação

- **Abra o link uns 2 minutos antes**, para a aplicação estar acordada.
- **O check-in só abre no dia da sessão.** A regra do sistema vale na demo também: a janela de check-in só abre num Domingo ou numa Terça, na data de hoje. Em outro dia dá para mostrar o painel, a escala, os indicadores do último domingo, a escala de preleções e os cartões, mas não marcar presença.
- **Casos prontos para mostrar na recepção** (no painel da sessão, busque por "souza"):
  - *Pedro Souza*: a próxima presença é a 4ª, e o cartão vai para Aguardando Avaliação.
  - *Marta Souza*: é de terça; aparece "Mudar para Domingo e carimbar".
  - *Davi Souza*: cartão retido, aguardando avaliação.
  - *Helena Souza*: avaliada, aguardando entrevista.
  - *Lucas Souza*: faltou mais de 3 semanas; o cartão expira e a recepção reinicia em P2.
  - O cadastro rápido cria alguém novo em P2 com a presença marcada.

## Recomeçar a demo do zero

1. No Neon, abra o projeto e toque em **SQL Editor**.
2. Cole e rode (**Run**):
   ```sql
   DROP SCHEMA public CASCADE;
   CREATE SCHEMA public;
   ```
3. No Render, abra o serviço e toque em **Manual Deploy → Restart service** (ou **Deploy latest commit**).

O banco é montado de novo com os dados fictícios, com datas relativas ao dia de hoje.

## Limites do plano gratuito

- **Render**: 512 MB de memória (a demo usa cerca de 300 MB) e hibernação após 15 minutos sem uso.
- **Neon**: 0,5 GB de armazenamento; o banco também hiberna e acorda sozinho no primeiro acesso.
- É uma demonstração: **não cadastre dados reais de assistidos** (LGPD).

---

<details>
<summary>Detalhes técnicos</summary>

- Perfil Spring `demo` (`application-demo.properties`): sem context path (o link abre direto no login), `show-sql` desligado, pool de 4 conexões.
- `DATABASE_URL` é convertida para JDBC por `config/DatabaseUrlConfig`; parâmetros que o driver não usa, como `channel_binding`, são descartados. Quem preferir pode usar `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD` no lugar dela.
- Banco vazio: `config/FlywayBancoVazioConfig` prepara o schema e o Flyway aplica V1–V27.
- Dados fictícios: `demo/DemoDataLoader`, só na primeira subida; a senha do `admin` é regravada a cada subida a partir de `DEMO_ADMIN_SENHA`.
- Imagem: `Dockerfile` em dois estágios (Maven + JRE 17), com memória ajustada para 512 MB.
</details>
