"""Fotografa as telas do sistema para o manual do usuário.

Roda no CI (workflow manual-capturas.yml) contra o sistema de verdade, num banco descartável com a carga
de simulação (nomes fictícios) e os logins de preparar.sql. Grava os PNG em scripts/manual/telas/.
"""
import base64
import hashlib
import hmac
import os
import subprocess
import sys
import time
import traceback
from datetime import datetime
from zoneinfo import ZoneInfo

from playwright.sync_api import sync_playwright

BASE = os.environ.get("BASE_URL", "http://localhost:8081/divinaluz")
SENHA = "manual123"
SAIDA = os.path.join(os.path.dirname(__file__), "telas")
os.makedirs(SAIDA, exist_ok=True)
HOJE = datetime.now(ZoneInfo("America/Sao_Paulo")).date()
DESKTOP = {"width": 1280, "height": 860}
CELULAR = {"width": 390, "height": 844}
falhas = []


def sql(consulta):
    saida = subprocess.run(["psql", "-h", "localhost", "-U", "postgres", "-d", "divinaluz_db", "-tAc", consulta],
                           capture_output=True, text=True, env={**os.environ, "PGPASSWORD": "admin"})
    if saida.returncode != 0:
        raise RuntimeError(saida.stderr)
    return saida.stdout.strip()


def um(consulta):
    valor = sql(consulta).splitlines()
    return valor[0] if valor else None


def foto(page, nome, seletor=None, inteira=True, linhas=5):
    try:
        page.wait_for_timeout(400)
        # O manual explica as colunas; com todas as linhas a tela fica comprida demais para ler.
        page.evaluate("n => document.querySelectorAll('table tbody').forEach("
                      "tb => [...tb.rows].slice(n).forEach(r => r.style.display = 'none'))", linhas)
        caminho = os.path.join(SAIDA, nome + ".png")
        if seletor:
            page.locator(seletor).first.screenshot(path=caminho)
        else:
            page.screenshot(path=caminho, full_page=inteira)
        print("ok", nome)
    except Exception:
        falhas.append(nome)
        traceback.print_exc()


def etapa(nome, funcao):
    try:
        funcao()
    except Exception:
        falhas.append(nome)
        print("FALHOU", nome)
        traceback.print_exc()


def entrar(browser, login, tela=DESKTOP, escala=1):
    contexto = browser.new_context(viewport=tela, device_scale_factor=escala, locale="pt-BR",
                                   timezone_id="America/Sao_Paulo")
    page = contexto.new_page()
    if login:
        page.goto(BASE + "/login")
        page.fill("#username", login)
        page.fill("#password", SENHA)
        page.click("button[type=submit]")
        page.wait_for_load_state("networkidle")
        # Login recusado volta ao /login, e sem esta conferência as fotos seguintes saem todas da tela de login.
        if "/login" in page.url:
            falhas.append("login-" + login)
            print("FALHOU login", login)
    return contexto, page


def csrf(page):
    return page.locator("input[name=_csrf]").first.get_attribute("value")


def post(page, caminho, dados):
    dados = {**dados, "_csrf": csrf(page)}
    resposta = page.request.post(BASE + caminho, form=dados, max_redirects=0)
    return resposta


def codigo_qr(segredo):
    minuto = int(time.time()) // 60
    assinatura = hmac.new(segredo.encode(), str(minuto).encode(), hashlib.sha256).digest()
    return base64.urlsafe_b64encode(assinatura).decode().rstrip("=")[:16]


def main():
    ids = {linha.split("|")[0]: linha.split("|")[1] for linha in sql(
        "SELECT login, id FROM assistido WHERE login IS NOT NULL").splitlines()}
    print("logins:", ids)
    dia_de_sessao = HOJE.weekday() in (1, 6)  # terça ou domingo
    data_hoje = HOJE.strftime("%d/%m/%Y")

    with sync_playwright() as p:
        browser = p.chromium.launch()

        # ---------------------------------------------------------------- sem login
        ctx, page = entrar(browser, None)
        page.goto(BASE + "/login"); foto(page, "01-login")
        page.goto(BASE + "/entrar"); foto(page, "02-entrar-por-codigo")
        ctx.close()
        ctx, page = entrar(browser, None, CELULAR, 2)
        page.goto(BASE + "/login"); foto(page, "01b-login-celular", inteira=False)
        ctx.close()

        # ---------------------------------------------------------------- recepção: abre a sessão de hoje
        rec, pr = entrar(browser, "recepcionista")
        sessao_id = None
        if dia_de_sessao:
            def abrir():
                pr.goto(BASE + "/sessao")
                foto(pr, "20-sessao-lista-antes")
                pr.fill("#data", data_hoje)
                pr.locator("form[action$='/sessao'] button[type=submit]").first.click()
                pr.wait_for_load_state("networkidle")
            etapa("abrir-sessao", abrir)
            sessao_id = um(f"SELECT id FROM sessao_assistencia WHERE data = DATE '{HOJE.isoformat()}'")
        print("sessão de hoje:", sessao_id)

        # ---------------------------------------------------------------- assistido (celular)
        ctx, page = entrar(browser, "assistido", CELULAR, 2)
        page.goto(BASE + f"/prontuario/{ids['assistido']}/cartao"); foto(page, "10-meu-cartao-celular", inteira=False)
        page.locator(".dl-nav-toggle").click(); foto(page, "11-menu-celular", inteira=False)
        if sessao_id:
            def pelo_celular():
                segredo = um(f"SELECT checkin_segredo FROM sessao_assistencia WHERE id = {sessao_id}")
                page.goto(BASE + f"/presenca/{sessao_id}/{codigo_qr(segredo)}")
                foto(page, "12-presenca-celular-confirmar")
                page.locator("form button[type=submit].btn-lg").first.click()
                page.wait_for_load_state("networkidle")
                foto(page, "13-presenca-celular-marcada")
            etapa("presenca-celular", pelo_celular)
        ctx.close()
        ctx, page = entrar(browser, "assistido")
        page.goto(BASE + f"/prontuario/{ids['assistido']}/cartao"); foto(page, "10b-meu-cartao")
        ctx.close()

        # ---------------------------------------------------------------- recepção: presenças e escala
        if sessao_id:
            def popular():
                pr.goto(BASE + f"/sessao/{sessao_id}")
                terca = sql("SELECT id FROM assistido WHERE ativo AND status_cartao = 'EM_TRATAMENTO' "
                            "AND dia_frequencia = 'TERCA_19H' AND login IS NULL ORDER BY id LIMIT 28").split()
                for aid in terca:
                    post(pr, f"/sessao/{sessao_id}/presenca/{aid}", {})
                domingo = sql("SELECT id FROM assistido WHERE ativo AND dia_frequencia = 'DOMINGO_08H' "
                              "AND login IS NULL ORDER BY id LIMIT 3").split()
                for aid in domingo:
                    post(pr, f"/sessao/{sessao_id}/ouvinte/{aid}", {})
                escalas = [("DIRIGENTE", "DIRIGENTE"), ("RECEPCIONISTA", "RECEPCIONISTA"),
                           ("RECEPCIONISTA", "RECEPCIONISTA"), ("ENTREVISTADOR", "ENTREVISTADOR"),
                           ("PASSE_LIMPEZA", "PASSISTA"), ("CAMARA_PASSE", "PASSISTA"),
                           ("CAMARA_PASSE", "PASSISTA"), ("CAMARA_PASSE", "PASSISTA")]
                usados = {}
                for posicao, funcao in escalas:
                    n = usados.get(funcao, 0)
                    tid = um(f"SELECT t.id FROM trabalhador t JOIN trabalhador_funcao f ON f.trabalhador_id = t.id "
                             f"JOIN assistido a ON a.id = t.assistido_id AND a.ativo WHERE f.funcao = '{funcao}' "
                             f"ORDER BY t.id OFFSET {n} LIMIT 1")
                    usados[funcao] = n + 1
                    if tid:
                        post(pr, f"/sessao/{sessao_id}/escala", {"posicao": posicao, "trabalhadorId": tid})
            etapa("popular-sessao", popular)

            pr.goto(BASE + f"/sessao/{sessao_id}"); foto(pr, "21-painel-da-sessao")
            foto(pr, "22-indicadores", "#painelIndicadores")
            nome = um("SELECT split_part(nome, ' ', 1) FROM assistido WHERE ativo AND dia_frequencia = 'TERCA_19H' "
                      "AND login IS NULL AND status_cartao = 'EM_TRATAMENTO' ORDER BY id DESC LIMIT 1")
            pr.goto(BASE + f"/sessao/{sessao_id}?busca={nome}#recepcao"); foto(pr, "23-recepcao-busca", "#recepcao")
            foto(pr, "24-escala", "#escala")
            pr.goto(BASE + f"/sessao/{sessao_id}/qrcode"); foto(pr, "25-qr-da-sessao", inteira=False)
        pr.goto(BASE + "/sessao"); foto(pr, "20-sessao-lista")

        # ---------------------------------------------------------------- recepção: cadastro e prontuário
        pr.goto(BASE + "/"); foto(pr, "30-listagem-prontuario")
        pr.goto(BASE + "/?mostrarInativos=true"); foto(pr, "30b-listagem-inativos", inteira=False)
        def cadastro():
            pr.goto(BASE + "/novo")
            pr.fill("#nome", "Maria Aparecida dos Santos")
            pr.wait_for_timeout(1500)
            foto(pr, "31-novo-cadastro")
        etapa("novo-cadastro", cadastro)
        alvo = um("SELECT a.id FROM assistido a WHERE a.ativo AND a.vinculo = 'ASSISTIDO' AND a.login IS NULL "
                  "AND a.status_cartao = 'EM_TRATAMENTO' AND EXISTS (SELECT 1 FROM avaliacao v WHERE v.assistido_id = a.id) "
                  "AND a.cep IS NOT NULL ORDER BY a.id LIMIT 1")
        pr.goto(BASE + f"/prontuario/{alvo}"); foto(pr, "32-prontuario")
        pr.goto(BASE + f"/prontuario/{alvo}/editar"); foto(pr, "33-editar-cadastro")
        pr.goto(BASE + f"/prontuario/{alvo}/cartao"); foto(pr, "34-cartao-pela-recepcao")
        def convite():
            pr.goto(BASE + f"/prontuario/{alvo}")
            pr.evaluate("document.querySelectorAll('form[target]').forEach(f => f.removeAttribute('target'))")
            pr.locator("form[action*='/celular/convite'] button").first.click()
            pr.wait_for_load_state("networkidle")
            foto(pr, "35-vincular-celular")
        etapa("convite", convite)
        codigo = um(f"SELECT codigo_cartao FROM assistido WHERE id = {alvo}")
        if sessao_id:
            pr.goto(BASE + f"/checkin/{codigo}"); foto(pr, "26-checkin-qr-do-cartao")
        pr.goto(BASE + "/prelecao"); foto(pr, "40-prelecoes")
        rec.close()

        # ---------------------------------------------------------------- celular da recepção (menu)
        ctx, page = entrar(browser, "recepcionista", CELULAR, 2)
        page.goto(BASE + "/"); foto(page, "30c-listagem-celular", inteira=False)
        ctx.close()

        # ---------------------------------------------------------------- avaliador
        ctx, page = entrar(browser, "avaliador")
        page.goto(BASE + "/"); foto(page, "50-avaliador-listagem")
        em_avaliacao = um("SELECT id FROM assistido WHERE ativo AND status_cartao = 'AGUARDANDO_AVALIACAO' "
                          "AND login IS NULL ORDER BY id LIMIT 1")
        page.goto(BASE + f"/prontuario/{em_avaliacao}/nova-avaliacao"); foto(page, "51-registrar-avaliacao")
        ctx.close()

        # ---------------------------------------------------------------- entrevistador
        ctx, page = entrar(browser, "entrevistador")
        page.goto(BASE + "/"); foto(page, "55-entrevistador-listagem")
        def entrevista():
            href = page.locator("a[href*='nova-entrevista?']").first.get_attribute("href")
            page.goto(href if href.startswith("http") else BASE.split("/divinaluz")[0] + href)
            foto(page, "56-registrar-entrevista")
        etapa("entrevista", entrevista)
        page.goto(BASE + f"/prontuario/{alvo}/nova-entrevista-avulsa?tipo=EXCEPCIONAL"); foto(page, "57-entrevista-excepcional")
        ctx.close()

        # ---------------------------------------------------------------- expositor / preletor
        ctx, page = entrar(browser, "expositor_preletor")
        page.goto(BASE + "/prelecao"); foto(page, "41-prelecoes-preletor")
        page.goto(BASE + "/prelecao/novo"); foto(page, "42-nova-prelecao")
        ctx.close()

        # ---------------------------------------------------------------- dirigente
        ctx, page = entrar(browser, "dirigente")
        page.goto(BASE + "/"); foto(page, "60-dirigente-listagem", inteira=False)
        page.goto(BASE + f"/prontuario/{alvo}"); foto(page, "61-prontuario-dirigente")
        page.goto(BASE + f"/trabalhadores/{alvo}"); foto(page, "62-perfis-de-trabalho")
        ctx.close()

        # ---------------------------------------------------------------- passista
        ctx, page = entrar(browser, "passista")
        page.goto(BASE + "/"); page.wait_for_load_state("networkidle"); foto(page, "65-passista-entrada", inteira=False)
        ctx.close()

        # ---------------------------------------------------------------- administrador
        ctx, page = entrar(browser, "admin")
        page.goto(BASE + "/auditoria"); foto(page, "70-auditoria", inteira=False)
        ctx.close()

        browser.close()

    print("falhas:", falhas)
    with open(os.path.join(SAIDA, "relatorio.txt"), "w") as f:
        f.write("hoje=%s sessao=%s\nfalhas=%s\n" % (HOJE, sessao_id, falhas))


if __name__ == "__main__":
    main()
    sys.exit(0)
