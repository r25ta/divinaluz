# Versão de demonstração na nuvem (gratuita)

A demo roda no **Render** (aplicação) com o banco no **Neon** (PostgreSQL). Os dois têm plano gratuito sem cartão de crédito. Tudo pode ser feito pelo navegador do celular.

- Perfil Spring: `demo` (arquivo `application-demo.properties`).
- Na primeira subida o banco vazio é montado sozinho (migrations V1–V27) e recebe **dados fictícios**: assistidos, trabalhadores, escala de preleções e a próxima sessão já escalada (`DemoDataLoader`).
- Senhas e dados do banco ficam só nas variáveis de ambiente do Render, nunca no repositório.

## 1. Banco no Neon (cerca de 5 min)

1. Acesse **neon.tech** e entre com a conta do GitHub.
2. Crie um projeto (nome `divinaluz`, região **AWS São Paulo** se aparecer, versão 16 ou mais nova).
3. No painel do projeto, clique em **Connect** e copie a *connection string*. Ela tem este formato:
   `postgresql://USUARIO:SENHA@ep-xxxx.sa-east-1.aws.neon.tech/neondb?sslmode=require`
4. Separe as três partes que o Render vai pedir:

| Variável | O que colocar | Exemplo |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://` + o que vem depois do `@`, mantendo `?sslmode=require` e removendo `&channel_binding=require` se houver | `jdbc:postgresql://ep-xxxx.sa-east-1.aws.neon.tech/neondb?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | o que vem entre `postgresql://` e `:` | `neondb_owner` |
| `SPRING_DATASOURCE_PASSWORD` | o que vem entre `:` e `@` | `npg_...` |

## 2. Aplicação no Render (cerca de 10 min + build)

1. Acesse **render.com** e entre com a conta do GitHub. Autorize o acesso ao repositório `divinaluz`.
2. Clique em **New +** e depois em **Blueprint**. Escolha o repositório `r25ta/divinaluz`. O Render lê o arquivo `render.yaml`, que já define um Web Service gratuito com Docker, publicado a partir do branch `claude/retomar-divinaluz-5i25l0`.
3. Preencha as variáveis pedidas:
   - as três do Neon (tabela acima);
   - `DEMO_ADMIN_SENHA`: a senha do login `admin` na demo. Use uma senha forte; ela também vale para o login de exemplo `assistido`.
4. Clique em **Apply**. O primeiro build leva de 5 a 10 minutos. Quando terminar, o Render mostra o link, no formato `https://divinaluz-demo.onrender.com`.

## 3. Logins da demo

| Login | Senha | O que vê |
|---|---|---|
| `admin` | a de `DEMO_ADMIN_SENHA` | Tudo (Administrador) |
| `assistido` | a mesma | Só o próprio cartão (Maria Aparecida Souza) e a escala de preleções |

A senha provisória `admin`/`password` **não** funciona na demo.

## 4. Antes e durante a apresentação

- **Acorde a aplicação uns 2 minutos antes.** No plano gratuito do Render ela "dorme" depois de 15 minutos sem acesso, e o primeiro acesso depois disso leva cerca de 1 minuto.
- **O check-in só abre no dia da sessão.** A regra do sistema vale na demo também: a janela de check-in só abre num Domingo ou numa Terça, na data de hoje. Em outro dia dá para mostrar o painel, a escala, os indicadores do último domingo, a escala de preleções e os cartões, mas não marcar presença.
- **Casos prontos para mostrar na recepção** (busque por "souza" no painel da sessão):
  - *Pedro Souza*: a próxima presença é a 4ª, e o cartão vai para Aguardando Avaliação.
  - *Marta Souza*: é de terça; aparece a opção "Mudar para Domingo e carimbar".
  - *Davi Souza*: cartão retido, aguardando avaliação.
  - *Helena Souza*: avaliada, aguardando entrevista.
  - *Lucas Souza*: faltou mais de 3 semanas; o cartão expira e a recepção reinicia em P2.
  - O cadastro rápido cria alguém novo em P2 com a presença marcada.

## 5. Recomeçar a demo do zero

No Neon, abra o **SQL Editor** e rode:

```sql
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
```

Depois, no Render, reinicie o serviço (menu **Manual Deploy** → **Restart service**, ou **Deploy latest commit**). O banco é montado de novo com os dados fictícios, com datas relativas ao dia de hoje.

## 6. Limites do plano gratuito

- Render: 750 horas por mês (suficiente para uma aplicação ligada o mês todo), 512 MB de memória e hibernação após 15 minutos sem uso.
- Neon: 0,5 GB de armazenamento; o banco também hiberna e acorda sozinho no primeiro acesso.
- É uma demonstração: não cadastre dados reais de assistidos (LGPD).
