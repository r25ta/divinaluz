# O sistema na nuvem (piloto, gratuito)

Este guia coloca o sistema no ar num endereço público, para **poucas pessoas da casa usarem com dados
reais**, em regime de piloto. São dois serviços gratuitos, sem cartão de crédito:

- **Neon**: guarda o banco de dados (PostgreSQL).
- **Render**: roda o sistema e gera o endereço.

Você vai copiar **um endereço** do Neon e colar no Render, junto com **uma senha** que você escolhe. O
resto já está configurado no repositório. Leva uns 20 minutos, e dá para fazer pelo navegador do
celular — menos a Parte 4 (backup), que é no computador.

> Nomes de botões mudam de vez em quando nesses sites. Se algum não bater exatamente, procure o
> equivalente com o mesmo sentido.

## Leia antes de começar

Três coisas que o plano gratuito **não** resolve, e que você assume ao fazer este piloto:

1. **O backup é seu.** O Neon gratuito guarda apenas **6 horas** de histórico para restauração. Isso
   não é backup. A Parte 4 configura uma cópia diária no seu computador, e ela não é opcional num
   piloto com dados reais.
2. **O sistema dorme.** Sem acesso por 15 minutos, o Render desliga o serviço; o primeiro acesso
   seguinte leva cerca de **1 minuto** para responder. Na prática: abra o sistema alguns minutos antes
   da sessão começar. Enquanto a recepção estiver usando, ele fica acordado.
3. **É dado sensível.** Prontuário de assistência espiritual é dado pessoal, e parte dele é dado de
   saúde. Cadastre **só as pessoas que o piloto precisa**, avise-as de que os dados estão num sistema,
   e não compartilhe o login de ninguém. O endereço do Render já usa HTTPS, o que protege os dados em
   trânsito — o resto depende de quem tem acesso.

---

## Parte 1 — Neon (o banco de dados)

### 1.1 Criar a conta
1. Abra **https://neon.tech** e toque em **Sign up**.
2. Escolha **Continue with GitHub** e autorize com a sua conta do GitHub.

### 1.2 Criar o projeto
Logo depois do cadastro o Neon abre a tela **Create project** (se não abrir, toque em **New project**).

| Campo | O que colocar |
|---|---|
| **Project name** | `divinaluz` |
| **Postgres version** | deixe a que vier (16 ou mais nova) |
| **Cloud provider** | **AWS** |
| **Region** | **AWS South America East 1 (São Paulo)**. Se não aparecer, use **AWS US East 1** |
| **Database name** (se pedir) | deixe `neondb` |

Toque em **Create project**.

> Se você já teve a demo antiga aqui, **crie um projeto novo** em vez de reaproveitar aquele: o banco da
> demo está cheio de dados fictícios, e não convém misturá-los com dados reais.

### 1.3 Copiar o endereço de conexão
1. No painel do projeto, toque em **Connect** (no topo ou no card "Connection").
2. Na janela que abre:
   - **Branch**: `production` (ou `main`, é o que vier).
   - **Database**: `neondb`.
   - **Connection pooling**: **desligue**. O endereço sem `-pooler` é o certo para esta aplicação.
3. Toque em **Show password**, para a senha aparecer no endereço, e depois em **Copy snippet**.

O endereço copiado tem este formato. Guarde-o: ele vai para o Render **e** para o backup da Parte 4.

```
postgresql://neondb_owner:npg_XXXXXXXX@ep-alguma-coisa-123456.sa-east-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require
```

Confira três coisas:
- começa com `postgresql://`;
- não tem `****` no lugar da senha (se tiver, faltou tocar em **Show password**);
- não tem `-pooler` no meio (se tiver, desligue o **Connection pooling** e copie de novo).

Pode colar do jeito que veio, com `?sslmode=require&channel_binding=require` e tudo.

---

## Parte 2 — Render (o sistema)

### 2.1 Criar a conta e ligar ao GitHub
1. Abra **https://render.com** e toque em **Get Started** (ou **Sign up**).
2. Escolha **GitHub** e autorize.
3. Quando o GitHub perguntar a quais repositórios o Render pode ter acesso, escolha **Only select
   repositories**, marque **`r25ta/divinaluz`** e toque em **Install** (ou **Save**).

### 2.2 Criar o serviço a partir do Blueprint
O repositório tem um `render.yaml` que já descreve tudo: servidor gratuito, Docker, o perfil `prod` e o
endereço de verificação.

1. No painel do Render, toque em **+ New** e escolha **Blueprint**.
2. Em **Connect a repository**, escolha **`r25ta/divinaluz`** e toque em **Connect**.

| Campo | O que colocar |
|---|---|
| **Blueprint Name** | `divinaluz` |
| **Branch** | **`main`** |
| **Blueprint Path** | deixe `render.yaml` |

3. O Render mostra o serviço **divinaluz** e pede **três variáveis**:

| Variável | O que colar |
|---|---|
| **`DATABASE_URL`** | o endereço inteiro copiado do Neon, começando com `postgresql://` |
| **`APP_BASE_URL`** | o endereço público do serviço, **sem barra no fim**: `https://divinaluz.onrender.com`. Você só vai saber o endereço exato depois do primeiro deploy — coloque esse palpite agora e corrija no passo 3.2 se vier diferente |
| **`ADMIN_SENHA_REDEFINIR`** | a senha que **você** vai usar para entrar como `admin` a primeira vez. No mínimo 8 caracteres. Esta variável é de uso único e será apagada no passo 3.3 |

4. Toque em **Deploy Blueprint** (ou **Apply**).

### 2.3 Acompanhar o primeiro deploy
1. Toque no serviço **divinaluz** e abra a aba **Logs** (ou **Events**).
2. O primeiro build leva de **5 a 10 minutos**: baixa as bibliotecas, compila e prepara a inicialização
   rápida. Depois disso a aplicação ainda leva cerca de **1 minuto** para subir.
3. Está pronto quando aparecer no log, e o status no topo ficar **Live** (verde):
   ```
   Successfully applied ... migrations to schema "public"
   Started DivinaluzApplication in ... seconds
   Senha do admin redefinida por ADMIN_SENHA_REDEFINIR. APAGUE essa variável ...
   ```
4. O endereço aparece no topo da página do serviço, no formato **`https://divinaluz.onrender.com`**
   (pode ter letras a mais no fim, se o nome já existir).

O banco começa **vazio**: nenhum assistido, nenhuma sessão. Só existe o login `admin`. Dados fictícios
não são mais cadastrados por ninguém — isto não é a demo.

---

## Parte 3 — Primeiro acesso e fechamento da porta

### 3.1 Entrar
Abra o endereço e entre com **`admin`** e a senha que você colocou em `ADMIN_SENHA_REDEFINIR`.

### 3.2 Conferir o endereço
Se o endereço que o Render deu for diferente do que você colocou em `APP_BASE_URL`, corrija agora: no
serviço, **Environment** → edite `APP_BASE_URL` → **Save Changes** (o Render reinicia sozinho). Esse
endereço é o que vai nos e-mails enviados aos assistidos (quando o envio estiver ligado — Parte 5). Os
QR codes do cartão e da sessão não dependem dele: usam o endereço por onde a pessoa abriu o sistema.

### 3.3 Trocar a senha do admin e apagar a variável
Esta ordem importa.

1. No sistema, abra o menu **Prontuário**, procure **admin** e entre em **Ver Prontuário** → **Editar Acesso**.
2. Na seção **Acesso ao Sistema**, preencha **Nova senha** e a confirmação, e salve.
3. No Render: **Environment** → apague a variável **`ADMIN_SENHA_REDEFINIR`** → **Save Changes**.

Enquanto essa variável existir, **cada reinício do serviço volta a gravar a senha antiga** — e todo
reinício acontece sozinho, quando o sistema acorda de um deploy. Apagá-la é o que torna a sua troca
definitiva.

### 3.4 Criar os acessos das pessoas da casa

> **Antes deste passo, faça a Parte 4 (backup).** A partir daqui entram dados de pessoas reais, e não
> convém existir um só dia de cadastro sem cópia de segurança.

Para cada pessoa do piloto, nesta ordem:

1. **Novo Cadastro** — cadastre a pessoa como assistida (todo trabalhador é, antes, um assistido). O
   acesso ao sistema é criado no próprio cadastro, na seção **Acesso ao Sistema**:
   - o **login** vem sugerido a partir do nome ("Maria da Silva Souza" → `maria.souza`) e pode ser
     alterado; ele é único, e o sistema avisa na hora se já estiver em uso;
   - a **senha** é obrigatória enquanto o envio de e-mail estiver desligado (o padrão — ver Parte 5);
   - cada **e-mail** só pode estar em um cadastro: se ele já existir, o sistema diz de quem é, com o link
     para o cadastro dessa pessoa — quase sempre é a mesma pessoa cadastrada antes.
2. **Cadastrar Trabalhador** — busque o nome e marque as funções dela na casa. Marcar uma função já
   torna o acesso dela de **Trabalhador**, sem escolher nada à mão. É a **função** que decide quais
   telas ela alcança:

   | Função | O que alcança |
   |---|---|
   | Dirigente | tudo |
   | Recepcionista | sessão, consulta, dados cadastrais e login, escala de preleções — não altera o prontuário |
   | Avaliador | consulta e registra a avaliação, propondo o novo tratamento |
   | Entrevistador | consulta e registra a entrevista, que comunica o tratamento ao assistido |
   | Expositor / Preletor | escala de preleções |
   | Passista | consulta |

   Todos alcançam também o próprio cartão.

Dois tropeços que vale saber antes:

- **As permissões são resolvidas no login.** Se você mudar a função de alguém, ela precisa sair e entrar
  de novo para a mudança valer.
- Para testar dois usuários ao mesmo tempo, use uma **janela anônima** para o segundo. As abas comuns do
  mesmo navegador dividem o mesmo login, e um formulário aberto antes de um novo login é recusado com o
  aviso "Esta página ficou desatualizada" (nada é gravado; basta recarregar).

Todo mundo, assistido ou trabalhador, entra direto no **próprio cartão de tratamento**. Os prontuários
ficam no menu **Prontuário** (no celular, atrás do botão ☰).

---

## Parte 3b — O dia de sessão

1. **Sessão** → **Abrir sessão** com a data de hoje. Isso já libera a marcação de presença.
2. Três formas de marcar presença, que respeitam as mesmas regras do cartão (só com o cartão Em
   Tratamento, regra dos 21 dias, dia da semana, uma presença por semana):
   - **busca por nome** no painel da sessão;
   - a recepção escaneia o **QR do cartão** da pessoa;
   - **QR da sessão** (botão no painel, abre numa nova aba): deixe essa tela num tablet ou monitor de
     frente para a fila; cada pessoa escaneia com o celular, entra com o próprio login se pedir e toca em
     **Confirmar minha presença**. O código muda a cada minuto, para uma foto dele não servir de casa.
     Cartão retido, expirado ou de outro dia: a pessoa é orientada a procurar a recepção.
3. Marcou alguém por engano: na lista **Assistidos Presentes**, o ícone de lixeira remove a presença e o
   cartão da pessoa é recalculado.
4. Ao terminar o trabalho: **Encerrar sessão**. Isso consolida o dia — presenças, escala e preletor não
   mudam mais. Se encerrou cedo demais, **Reabrir sessão** funciona só no próprio dia.
5. A casa não abriu (feriado, chuva): **Cancelar ou excluir esta sessão** → informe o motivo. Essa semana
   não conta como falta na regra dos 21 dias.

---

## Parte 4 — Backup (não é opcional)

Isto roda no **seu computador**, não na nuvem, e é a sua garantia contra perder o banco.

### 4.1 Guardar o endereço do banco na sua conta do Windows
Abra o PowerShell e rode, com a sua connection string do Neon no lugar:

```powershell
setx DIVINALUZ_DATABASE_URL "postgresql://neondb_owner:SENHA@ep-...neon.tech/neondb?sslmode=require"
```

Feche e reabra o PowerShell. A string fica na sua conta de usuário, **fora do repositório** e fora da
tarefa agendada.

### 4.2 Testar uma vez à mão
```powershell
cd C:\Users\twent\OneDrive\Apps\divinaluz
.\scripts\backup-nuvem.ps1
```

Deve terminar com `Conferido: o dump e legivel` e `Pronto. 1 backup(s) em ...`. O arquivo vai para
`C:\Users\twent\OneDrive\Backups\divinaluz`, com data e hora no nome. O script confere o arquivo gerado
com o `pg_restore`, então um backup corrompido falha na hora, e não no dia em que você precisar dele.

### 4.3 Agendar todo dia
```powershell
schtasks /Create /TN "Backup DivinaLuz" /SC DAILY /ST 21:00 /TR "powershell -NoProfile -ExecutionPolicy Bypass -File \"C:\Users\twent\OneDrive\Apps\divinaluz\scripts\backup-nuvem.ps1\""
```

Guarda os últimos 60 dias e apaga os mais antigos. Para rodar agora e conferir:
`schtasks /Run /TN "Backup DivinaLuz"`.

> O dia de sessão é o que mais importa. Se quiser uma cópia extra logo depois de domingo e terça, crie
> outra tarefa com `/SC WEEKLY /D SUN,TUE /ST 12:00`.

### 4.4 Como restaurar (teste isso uma vez, com calma)
Para trazer um backup de volta a um banco local e conferir que está tudo lá:

```powershell
$bin = "C:\Program Files\PostgreSQL\18\bin"
$env:PGPASSWORD = "admin"
& "$bin\psql.exe" -U postgres -h localhost -c "CREATE DATABASE divinaluz_restaurado;"
& "$bin\pg_restore.exe" --no-owner --no-privileges -U postgres -h localhost -d divinaluz_restaurado "C:\Users\twent\OneDrive\Backups\divinaluz\divinaluz_AAAA-MM-DD_HHMM.dump"
& "$bin\psql.exe" -U postgres -h localhost -d divinaluz_restaurado -c "SELECT count(*) FROM assistido;"
```

Para restaurar **na nuvem** (num projeto Neon novo), é o mesmo `pg_restore`, trocando o `-U/-h/-d` pela
connection string: `pg_restore --no-owner --no-privileges -d "postgresql://..." arquivo.dump`.

> **LGPD:** esses arquivos contêm os dados reais dos assistidos. Guarde-os só onde você controla o
> acesso, e não os envie por e-mail nem por aplicativo de mensagem.

---

## Parte 5 — E-mail (opcional)

Sem configurar nada, o sistema funciona: a recepção digita a senha de acesso no cadastro, e a pessoa
entra com login e senha. **Enquanto o envio de e-mail estiver desligado, o próprio cadastro exige a
senha**, mesmo para quem informa e-mail — sem envio, o código de entrada não chegaria e a pessoa ficaria
sem como entrar.

Com o envio ligado, quem tem e-mail no cadastro também pode entrar sem senha: na tela de login, toca em
**entre com seu e-mail**, informa o endereço e digita um código de 6 dígitos que chega na caixa dela. Nesse caso a senha
no cadastro passa a ser opcional.

> Cadastros feitos **antes** de 2026-10-03 com e-mail podem estar sem senha. Se alguém assim disser que
> não consegue entrar, abra o prontuário → **Editar Acesso** e defina uma senha.

Para ligar o envio de verdade, no Render → **Environment**, adicione:

| Variável | Valor |
|---|---|
| `MAIL_HABILITADO` | `true` |
| `MAIL_HOST` | `smtp.gmail.com` |
| `MAIL_PORT` | `587` |
| `MAIL_USERNAME` | o endereço Gmail que vai enviar |
| `MAIL_PASSWORD` | uma **senha de app** do Google (não a senha da conta — gere em *Conta Google → Segurança → Senhas de app*, com a verificação em duas etapas ligada) |
| `MAIL_REMETENTE` | o mesmo endereço do `MAIL_USERNAME` |

Confira que `APP_BASE_URL` está correta antes, senão o endereço chega apontando para o lugar errado.

Ligado o envio, o assistido com e-mail entra assim: na tela de login, **entre com seu e-mail** → informa o endereço →
recebe um código de 6 dígitos (que aparece até no assunto da mensagem, para ler sem abrir o e-mail) →
digita o código. O código vale **15 minutos** e serve uma única vez. Se ele deixar marcado **Lembrar
deste aparelho**, o celular dele continua entrando sozinho por 90 dias, sem pedir código de novo — e
isso sobrevive à hibernação e aos deploys do Render.

Se alguém disser que o código não chegou, abra o prontuário da pessoa e use **Reenviar Código**.

---

## Quando algo dá errado

| Sintoma | O que fazer |
|---|---|
| O endereço demora de 1 a 2 minutos para abrir | Normal: o serviço dormiu depois de 15 minutos sem uso e está acordando |
| Log com `DATABASE_URL precisa começar com postgresql://` ou `sem usuário e senha` | O endereço foi colado incompleto. Copie de novo no Neon (com **Show password**) |
| Log com `password authentication failed` | A senha no endereço está errada ou veio como `****` |
| Log com `Connection refused` ou `timeout` | Confira se o endereço é o do projeto certo; abrir o projeto no painel do Neon já acorda o banco |
| Log com `ADMIN_SENHA_REDEFINIR tem menos de 8 caracteres` | Escolha uma senha maior nessa variável |
| Log com `APP_BASE_URL não definida` | Só um aviso: o sistema sobe, mas os endereços nos e-mails sairiam errados (só importa com o envio ligado) |
| A senha do admin "voltou" sozinha | Faltou apagar `ADMIN_SENHA_REDEFINIR` (passo 3.3) |
| Alguém mudou de função e continua sem ver as telas novas | Ela precisa sair e entrar de novo: as permissões são resolvidas no login |
| "Esta página ficou desatualizada" ao salvar | Houve um novo login no mesmo navegador (outra aba, ou o sistema reiniciou e foi preciso entrar de novo) depois que a página foi aberta. Nada foi gravado: recarregue e salve de novo |
| "Você não tem permissão para isto" | A função da pessoa não alcança aquela tela (Parte 3, passo 3.4). Se ela acabou de ganhar uma função, sair e entrar de novo |
| QR da sessão não aparece na tela da recepção | Recarregue a tela do QR. Se a sessão foi encerrada, ele some de propósito — reabra a sessão (só no próprio dia) |
| Presença pelo celular recusada | A tela diz o motivo: cartão aguardando avaliação/entrevista, expirado por ausência ou de outro dia — a recepção resolve |
| `Suspended` no serviço, no fim do mês | O plano gratuito dá 750 horas de instância por mês para a conta inteira; volta no dia 1º |

## Limites do plano gratuito

- **Render**: 512 MB de memória (o sistema usa cerca de 300 MB), hibernação após 15 minutos sem uso e
  750 horas de instância por mês para a conta.
- **Neon**: 0,5 GB de armazenamento por projeto — muito mais do que um prontuário de texto consome — e
  apenas 6 horas de histórico para restauração, que é a razão da Parte 4.
- Nenhum dos dois promete disponibilidade. Para um piloto, serve; para a casa depender disso todos os
  domingos, o passo seguinte é um plano pago de uns poucos dólares no Render, que elimina a hibernação.

## Para desligar tudo

1. **Render** → serviço `divinaluz` → **Settings** → **Delete Service**.
2. **Neon** → projeto → **Settings** → **Delete project**.
3. Guarde o último backup antes, se os dados ainda importarem.

## Detalhes técnicos (para quem for mexer no código)

- Perfil Spring `prod` (`application-prod.properties`): sem context path (o endereço abre direto no
  login), `show-sql` desligado (um SQL com parâmetros no log exporia dado de assistido), inicialização
  tardia, pool de 4 conexões e `server.forward-headers-strategy=framework` (atrás do proxy do Render,
  para os endereços dentro dos QR codes saírem com `https://`).
- Banco por `DATABASE_URL`, convertida para JDBC por `config/DatabaseUrlConfig`; porta por `PORT`.
- `config/SenhaAdminInicial` (`@Profile("prod")`) é quem aplica `ADMIN_SENHA_REDEFINIR` e avisa quando
  falta `APP_BASE_URL`.
- Schema criado e atualizado pelo Flyway na subida, a partir de um banco vazio.
- Imagem: `Dockerfile` em dois estágios com CDS, que é o que mantém a subida em ~1 minuto com pouca CPU.
