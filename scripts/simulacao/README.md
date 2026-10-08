# Carga de simulação — seis meses de funcionamento da casa

Popula o banco com um semestre **fictício** de funcionamento, para testar o sistema com volume real:
telas, filas, indicadores, desempenho e o próprio fluxo da casa. Tudo sai das regras do sistema,
como se a recepção, os Avaliadores e os Entrevistadores tivessem usado o prontuário de
**05/04/2026 a 29/09/2026**.

| Arquivo | Para quê |
|---|---|
| `carga-simulacao.sql` | A carga. **Gerado** — não edite à mão. |
| `conferir-simulacao.sql` | Confere 20 regras do sistema direto no banco (só leitura) e mostra o retrato atual. |
| `remover-simulacao.sql` | Apaga a simulação inteira e deixa os dados reais como estavam. |
| `gerar_simulacao.py` | O simulador que gera a carga (Python 3.10+, sem dependências). |

## O que entra

- **52 sessões** (todo domingo 8h e toda terça 19h), todas encerradas. Duas foram **canceladas**: 21/04 (Tiradentes) e 19/07 (falta de energia). Três foram encerradas pelo sistema às 23:59:59.
- **45 a 50 pessoas por sessão** (média 49,1), contando trabalhadores, ouvintes e visitantes de outro dia.
- **36 trabalhadores**, 18 por dia (Dirigentes, Recepcionistas, Avaliadores, Entrevistadores, Expositores e Passistas), todos também assistidos. A **escala de cada sessão** respeita o mínimo de cada setor (`PosicaoSessao`), e só escala quem veio.
- **159 cadastros** (fora 4 preletores convidados), entre eles 6 crianças (2 hoje em P4). Os cadastros de entrada são completos (com endereço) ou rápidos (só nome, nascimento e sexo; são 65).
- **52 preleções**: 11 feitas por convidados, 2 trocas emergenciais de preletor, e as preleções dos dias cancelados também canceladas.
- **~2.450 presenças**, **411 avaliações** e **442 cartões encerrados** (357 concluídos, 45 incompletos por tempo, 35 altas e 5 trocas de tratamento).
- **524 entrevistas** dos três tipos: 392 **do tratamento** (depois da avaliação), 109 **da 1ª sessão** (de quem quis, no dia em que o tratamento começou) e 23 **excepcionais** (pedidas pelo assistido).
- **Regras da casa:** a Avaliação nunca acontece no dia da 4ª presença. Ela vem de 1 a 5 dias depois (a data é livre) ou na próxima vinda da pessoa. A Entrevista do tratamento acontece na vinda seguinte à avaliação, e também nunca no dia da 4ª presença.
- Situações do caminho: faltas, ausências longas com reinício em P2, cartão expirado lido pelo celular, 6 trocas de dia de assistência (com histórico), visitas de outro dia como ouvinte, volta depois da alta, quatro cadastros desativados.

**Retrato ao final** (o que as telas mostram):

| Status do cartão | Assistidos | Trabalhadores |
|---|---|---|
| Em Tratamento | 78 | 9 |
| Em Avaliação | 10 | 4 |
| Aguardando Entrevista | 17 | 2 |
| Incompleto por Tempo | 4 | — |
| Alta | 14 | 21 |

O tratamento atual de quem está em ciclo se distribui entre P2, A2, P1, P3C, CH, P3B, P3E, P3A, P3DC e P4.

**O que não entra:** nenhum login. Ninguém da simulação consegue entrar no sistema. Os e-mails terminam em `@sim.invalid`, um domínio que não existe, então nada é enviado a ninguém de verdade.

## Proteções

- **Tudo ou nada:** a carga e a remoção rodam numa transação só.
- **Precisa da V43:** a carga grava os tipos de entrevista, então recusa rodar num banco que ainda não tem essa migration. Publique primeiro a versão do sistema que a traz.
- **Não mistura com dados reais:** a carga se recusa a rodar se já houver qualquer sessão, presença, preleção, avaliação ou entrevista entre 05/04 e 02/10/2026.
- **Não carrega duas vezes:** a carga cria a tabela `simulacao_registro`, com o id de cada linha gravada. Se ela já existe, a carga para.
- **A remoção apaga só o que é da simulação.** Isso inclui o que o sistema gravou depois para as pessoas simuladas, como uma presença carimbada numa sessão real ou um celular vinculado. Escala, preleções e pessoas reais ficam. Se uma **preleção real** tiver um preletor simulado, a remoção para e diz qual é, para você trocar o preletor antes.

Testado num PostgreSQL com o schema das 43 migrations: as 20 regras de `conferir-simulacao.sql` dão zero problemas. Carregar e depois remover deixa o banco **idêntico** ao de antes; só a numeração dos ids avança.

## Como rodar no banco da nuvem (Windows)

Use o mesmo endereço do banco que o backup usa (`DIVINALUZ_DATABASE_URL`, ver `DEPLOY-NUVEM.md`, Parte 4). O `psql.exe` vem junto com o `pg_dump.exe`.

0. **Publique antes a versão do sistema com a V43** (o merge do PR no `main`; o Render faz o deploy e o Flyway aplica a migration na subida). Sem ela, a carga para com um aviso e não grava nada.

1. **Faça um backup antes.** É o seu ponto de volta se algo sair do previsto:

   ```powershell
   .\scripts\backup-nuvem.ps1
   ```

2. **Carregue** (de dentro da pasta do projeto):

   ```powershell
   $psql = (Get-ChildItem "C:\Program Files\PostgreSQL\*\bin\psql.exe" | Select-Object -Last 1).FullName
   & $psql $env:DIVINALUZ_DATABASE_URL -f scripts\simulacao\carga-simulacao.sql
   ```

   Termina com a contagem por tabela e `COMMIT`. Leva poucos segundos.

3. **Confira** (opcional, mas recomendado):

   ```powershell
   & $psql $env:DIVINALUZ_DATABASE_URL -f scripts\simulacao\conferir-simulacao.sql
   ```

   Todas as linhas de `problemas` precisam dar `0`.

4. **Para tirar tudo depois:**

   ```powershell
   & $psql $env:DIVINALUZ_DATABASE_URL -f scripts\simulacao\remover-simulacao.sql
   ```

Não precisa reiniciar o sistema nem fazer deploy: as telas já mostram os dados no próximo acesso.

## Para gerar de novo

```bash
python3 scripts/simulacao/gerar_simulacao.py               # mesma carga (semente 2118)
python3 scripts/simulacao/gerar_simulacao.py --semente 7   # outro semestre, mesmas regras
```

O simulador anda dia a dia. Cada presença, avaliação, entrevista, troca de dia ou de tratamento passa
por funções que reproduzem o `TratamentoService` e o `CheckinService`. Uma violação de regra para a
geração com erro em vez de virar dado. Se as regras do sistema mudarem, mude as funções da classe
`Casa` junto, e as consultas de `conferir-simulacao.sql` se for o caso.
