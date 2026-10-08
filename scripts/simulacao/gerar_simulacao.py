#!/usr/bin/env python3
"""
Gera scripts/simulacao/carga-simulacao.sql: seis meses de funcionamento simulado da casa
(05/04/2026 a 29/09/2026, domingos 8h e terças 19h), para testar o sistema com volume real.

Não grava nada sozinho: o SQL gerado é que vai ao banco (ver README.md nesta pasta). O simulador
anda dia a dia e cada passo passa por funções que reproduzem, linha a linha, as regras do
TratamentoService/CheckinService (registrarSessao, registrarAvaliacao, registrarEntrevista,
definirTratamento, alterarDiaFrequencia, registrarOuvinte) — por isso o resultado é o mesmo que a
recepção, o Avaliador e o Entrevistador teriam produzido usando o sistema. Qualquer violação de
regra aqui é um erro do simulador (AssertionError), nunca um dado gravado.

Uso:  python3 gerar_simulacao.py            (semente fixa 2118: o SQL sai sempre igual)
      python3 gerar_simulacao.py --semente 7
"""
import argparse
import random
import unicodedata
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path

# ------------------------------------------------------------------------------------------------
# Calendário
# ------------------------------------------------------------------------------------------------
INICIO = date(2026, 4, 5)      # domingo
FIM = date(2026, 9, 29)        # terça — a última sessão simulada
FIM_AVALIACOES = date(2026, 10, 2)  # avaliação tem data livre: pode cair depois da última sessão
DOMINGO, TERCA = "DOMINGO_08H", "TERCA_19H"
WEEKDAY = {DOMINGO: 6, TERCA: 1}   # date.weekday(): segunda=0 … domingo=6

CANCELADAS = {
    date(2026, 4, 21): (datetime(2026, 4, 20, 15, 10), "Feriado de Tiradentes: a casa não abriu."),
    date(2026, 7, 19): (datetime(2026, 7, 19, 7, 35), "Falta de energia no bairro desde a madrugada."),
}
# Sessões que ninguém encerrou e o sistema encerrou às 23:59:59 (EncerramentoAutomaticoSessoes).
ENCERRADAS_AUTOMATICAMENTE = {date(2026, 5, 12), date(2026, 8, 2), date(2026, 9, 15)}

SESSOES_POR_AVALIACAO = 4
TOLERANCIA = 21

MIN_POR_SESSAO, MAX_POR_SESSAO = 45, 50

# Mínimos de PosicaoSessao (fonte: PosicaoSessao.java).
POSICOES = [
    ("DIRIGENTE", 1, ["DIRIGENTE"]),
    ("RECEPCIONISTA", 2, ["RECEPCIONISTA"]),
    ("SECRETARIA", 1, ["RECEPCIONISTA"]),
    ("ENTREVISTADOR", 1, ["ENTREVISTADOR"]),
    ("PASSE_LIMPEZA", 1, ["PASSISTA"]),
    ("CAMARA_PASSE", 4, ["PASSISTA"]),
    ("DIRIGENTE_CAMARA", 1, ["PASSISTA"]),
    ("P3B", 3, ["PASSISTA"]),
]

# Equipe de cada dia (18 trabalhadores): funções permanentes (TipoTrabalhador).
EQUIPE = [
    {"DIRIGENTE", "EXPOSITOR_PRELETOR"},
    {"DIRIGENTE", "AVALIADOR"},
    {"RECEPCIONISTA"},
    {"RECEPCIONISTA"},
    {"RECEPCIONISTA"},
    {"RECEPCIONISTA", "EXPOSITOR_PRELETOR"},
    {"ENTREVISTADOR"},
    {"ENTREVISTADOR", "AVALIADOR"},
    {"AVALIADOR", "PASSISTA"},
    {"PASSISTA", "EXPOSITOR_PRELETOR"},
] + [{"PASSISTA"} for _ in range(8)]

TRATAMENTOS_ADULTO = {"P1": 16, "P2": 6, "P3A (ou P3F)": 8, "P3C": 12, "P3E": 12,
                      "P3DC / P3V": 5, "A2": 14, "CH": 10, "P3B": 8}

# ------------------------------------------------------------------------------------------------
# Massa de nomes e textos (fictícios)
# ------------------------------------------------------------------------------------------------
NOMES_F = """Ana Maria Juliana Fernanda Patrícia Aline Camila Amanda Bruna Jéssica Letícia Mariana
Gabriela Vanessa Larissa Beatriz Renata Daniela Tatiana Simone Cláudia Sandra Luciana Adriana
Cristina Rosana Márcia Sônia Vera Regina Helena Lúcia Teresa Fátima Aparecida Rita Célia Eliane
Rosângela Denise Silvana Viviane Priscila Débora Raquel Natália Carolina Isabela Lívia Sabrina
Tânia Neide Marlene Irene Glória Joana Elisa Roberta Michele Karina Talita Yasmin Alice Laura""".split()
NOMES_M = """José João Antônio Francisco Carlos Paulo Pedro Lucas Luiz Marcos Luís Gabriel Rafael
Daniel Marcelo Bruno Eduardo Felipe Raimundo Rodrigo Manoel Mateus André Fernando Fábio Leonardo
Gustavo Guilherme Leandro Tiago Anderson Ricardo Márcio Jorge Sebastião Alexandre Roberto Edson
Diego Vítor Sérgio Cláudio Mário Renato Wagner Joaquim Geraldo Adriano Moisés Davi Samuel Otávio
Henrique Rogério Valter Nelson Hélio Ivan Júlio Caio Enzo Arthur Heitor""".split()
SOBRENOMES = """Silva Santos Oliveira Souza Rodrigues Ferreira Alves Pereira Lima Gomes Costa Ribeiro
Martins Carvalho Almeida Lopes Soares Fernandes Vieira Barbosa Rocha Dias Nascimento Andrade Moreira
Nunes Marques Machado Mendes Freitas Cardoso Ramos Gonçalves Santana Teixeira Araújo Pinto Correia
Moura Campos Reis Cavalcanti Monteiro Batista Farias Duarte Rezende Prado Siqueira Brandão Queiroz
Fonseca Barros Peixoto Tavares Bezerra Medeiros Pires Coelho Matos Guimarães Sampaio""".split()
BAIRROS = ["Vila Mariana", "Saúde", "Jabaquara", "Ipiranga", "Vila Prudente", "Mooca", "Tatuapé",
           "Santana", "Tucuruvi", "Lapa", "Pinheiros", "Butantã", "Campo Limpo", "Santo Amaro",
           "Cidade Ademar", "Vila Guilherme", "Penha", "Sacomã", "Cursino", "Vila Clementino"]
RUAS = ["Rua das Hortênsias", "Rua Domingos de Morais", "Avenida Jabaquara", "Rua Vergueiro",
        "Rua Cubatão", "Rua Loefgren", "Rua Tamandaré", "Avenida Indianópolis", "Rua Afonso Celso",
        "Rua Santa Cruz", "Rua das Rosas", "Rua Pedro de Toledo", "Rua Itapiru", "Rua Borges Lagoa",
        "Rua Dr. Diogo de Faria", "Avenida Ceci", "Rua Caramuru", "Rua Abílio Soares"]
ESTADOS_CIVIS = ["Solteiro(a)", "Casado(a)", "Casado(a)", "Casado(a)", "Divorciado(a)", "Viúvo(a)",
                 "União Estável"]

TEMAS = [
    "O Evangelho no Lar", "A Prece e seus efeitos", "Parábola do Bom Samaritano", "Fora da caridade não há salvação",
    "Bem-aventurados os aflitos", "A fé que transporta montanhas", "Amai os vossos inimigos",
    "O perdão das ofensas", "A paciência", "Os laços de família", "Honrai a vosso pai e a vossa mãe",
    "Não separeis o que Deus juntou", "Buscai e achareis", "A porta estreita", "Muitos os chamados, poucos os escolhidos",
    "O homem de bem", "A indulgência", "A lei de amor", "A reencarnação e a justiça divina",
    "Os obsessores e a obsessão", "O passe e a fluidoterapia", "A influência dos Espíritos em nossos pensamentos",
    "Pedi e obtereis", "A cólera", "O orgulho e a humildade", "A caridade material e a caridade moral",
    "Parábola do filho pródigo", "A mediunidade com Jesus", "O Consolador prometido", "A vigilância",
    "Dai de graça o que de graça recebestes", "A felicidade não é deste mundo", "O suicídio e suas consequências",
    "Causas atuais das aflições", "Causas anteriores das aflições", "O dever", "A virtude",
    "O mal e o remédio", "Esquecimento do passado", "O Cristo consolador", "A gratidão",
    "Instruções dos Espíritos sobre a prece", "A Lei de Trabalho", "A Lei do Progresso", "Os vícios e a reforma íntima",
    "A depressão à luz do Espiritismo", "Sementeira e colheita", "O valor do tempo", "A esperança",
    "Jesus e a mulher samaritana", "O jugo leve", "Não julgueis para não serdes julgados",
]

HISTORICOS = [
    "Relata {queixa}. {contexto}",
    "Chegou à avaliação dizendo que {queixa}. {contexto}",
    "Informa {queixa}; {contexto_min}",
]
QUEIXAS = {
    "fisico": ["dores crônicas nas costas e cansaço constante", "pressão alta em acompanhamento médico",
               "recuperação de cirurgia recente", "insônia e dores de cabeça frequentes"],
    "emocional": ["tristeza persistente e desânimo", "crises de ansiedade antes de dormir",
                  "luto recente pela perda da mãe", "sensação de vazio e choro fácil"],
    "espiritual": ["pesadelos recorrentes e sensação de presença em casa", "irritação sem motivo aparente",
                   "pensamentos intrusivos que atrapalham a concentração", "sensação de peso ao chegar em casa"],
    "familia": ["conflitos frequentes com o cônjuge", "preocupação com o filho adolescente",
                "dificuldade de relacionamento com os pais idosos", "desentendimentos no ambiente de trabalho"],
    "vicio": ["dificuldade para largar o cigarro", "uso de álcool nos fins de semana que tem preocupado a família"],
    "infantil": ["agitação e dificuldade de concentração na escola", "medo do escuro e sono agitado"],
}
CONTEXTOS = ["Faz o Evangelho no Lar com irregularidade.", "Não tem o hábito da prece diária.",
             "Está em acompanhamento psicológico.", "Trabalha em turnos e dorme pouco.",
             "Mora com a família e diz ter apoio em casa.", "Mudou de emprego há pouco tempo.",
             "Frequentou outra casa espírita anos atrás.", "Veio por indicação de uma amiga trabalhadora da casa."]
CONTEXTOS_MIN = ["diz que melhorou desde o último cartão", "percebe pouca diferença até aqui",
                 "teve uma semana difícil, mas manteve a frequência", "relata mais tranquilidade em casa"]
OBS_AVALIACAO = [
    "Manter o Evangelho no Lar semanalmente, no mesmo dia e horário.",
    "Prece ao deitar e ao acordar; leitura de uma página de O Evangelho Segundo o Espiritismo por dia.",
    "Recomendada a água fluidificada e evitar ambientes de discussão.",
    "Procurar o médico para os exames de rotina; o tratamento espiritual não substitui o médico.",
    "Leitura de 'Nosso Lar' e participação na escola de aprendizes quando possível.",
    "Vigilância nos pensamentos; buscar atividades de caridade.",
    "Conversar com a família sobre o Evangelho no Lar e convidar quem quiser participar.",
]
OBS_ENTREVISTA = [
    "Tratamento comunicado; assistido compreendeu as recomendações.",
    "Explicado o funcionamento do novo tratamento e a importância da frequência.",
    "Assistido emocionado; reforçada a prece diária e o Evangelho no Lar.",
    "Orientado a manter o acompanhamento médico em paralelo.",
    "Combinado retorno na próxima semana, no mesmo horário.",
    "Assistido relatou melhora e agradeceu; reforçadas as leituras recomendadas.",
]
OBS_ALTA = [
    "Alta comunicada. Pode continuar frequentando as palestras como ouvinte.",
    "Alta: boa evolução nos últimos cartões. Convidado para a escola de aprendizes.",
    "Alta comunicada; orientado a manter o Evangelho no Lar e voltar se precisar.",
]

OBS_PRIMEIRA_SESSAO = [
    "Acolhimento: explicado como funcionam as sessões, o passe e o cartão de 4 semanas.",
    "Primeira vez numa casa espírita; tirou dúvidas sobre o passe e a água fluidificada.",
    "Contou o motivo da vinda; orientado sobre a frequência semanal e o Evangelho no Lar.",
    "Veio por indicação de familiar; explicado o tratamento de entrada (P2) e os horários.",
    "Recomeço depois de um tempo afastado; reforçada a importância de não faltar.",
]
OBS_EXCEPCIONAL = [
    "Pediu para conversar sobre o luto recente na família; ouvido e orientado a manter a prece.",
    "Procurou a casa por conflito no trabalho; orientado a buscar o diálogo e a vigilância.",
    "Quis tirar dúvidas sobre mediunidade; indicada a escola de aprendizes.",
    "Pediu orientação sobre um familiar internado; combinada vibração no grupo de assistência.",
    "Relatou sonhos recorrentes que o assustam; orientado sobre a prece antes de dormir.",
    "Pediu ajuda para retomar o Evangelho no Lar com a família.",
]

CONVIDADOS = [
    ("Rubens Albuquerque Neto", "(11) 98123-4410", "Centro Espírita Caminho da Luz", "rubens.neto@sim.invalid"),
    ("Dalva Moreira Pacheco", "(11) 97456-1203", "Grupo Espírita Bezerra de Menezes", None),
    ("Osvaldo Teixeira Lins", "(11) 99210-8877", "Federação Espírita do Estado de São Paulo", "osvaldo.lins@sim.invalid"),
    ("Iracema Lopes Furtado", "(11) 96655-3091", "Lar Fraterno Irmã Scheilla", None),
]


# ------------------------------------------------------------------------------------------------
# Modelo
# ------------------------------------------------------------------------------------------------
@dataclass
class Presenca:
    data: date
    ouvinte: bool
    numero: int | None
    k: int = 0


@dataclass
class Pessoa:
    k: int
    nome: str
    sexo: str
    nascimento: date
    dia: str | None = None
    vinculo: str = "ASSISTIDO"
    tratamento: str | None = None
    ciclo: date | None = None
    status: str = "EM_TRATAMENTO"
    ativo: bool = True
    funcoes: set = field(default_factory=set)
    presencas: list = field(default_factory=list)
    avaliacoes: list = field(default_factory=list)
    entrevistas: list = field(default_factory=list)
    avulsas: list = field(default_factory=list)      # entrevista da 1ª sessão e excepcional (V43)
    cartoes: list = field(default_factory=list)
    historico_dia: list = field(default_factory=list)
    dados: dict = field(default_factory=dict)
    # comportamento
    assiduidade: float = 0.88
    perfil: str = "emocional"
    ausente_ate: date | None = None
    saiu: bool = False
    frequenta_apos_alta: bool = False
    volta_apos_alta_em: date | None = None
    avaliacao_marcada: date | None = None
    cadastrado: bool = False

    @property
    def trabalhador(self):
        return bool(self.funcoes)

    def idade(self, em):
        return em.year - self.nascimento.year - ((em.month, em.day) < (self.nascimento.month, self.nascimento.day))


class RegraVioladaNoSimulador(AssertionError):
    pass


def regra(condicao, mensagem):
    if not condicao:
        raise RegraVioladaNoSimulador(mensagem)


def inicio_semana(d):
    return d - timedelta(days=(d.weekday() + 1) % 7)   # domingo anterior ou o próprio


def fim_semana(d):
    return inicio_semana(d) + timedelta(days=6)


class Casa:
    """Estado do banco + as regras do TratamentoService."""

    def __init__(self, rnd):
        self.rnd = rnd
        self.pessoas = []
        self.prelecoes = {}       # data -> dict
        self.sessoes = {}         # data -> dict
        self.seq = {"presenca": 0, "avaliacao": 0, "entrevista": 0, "cartao": 0, "historico": 0}

    def novo_k(self, tipo):
        self.seq[tipo] += 1
        return self.seq[tipo]

    # --- consultas -----------------------------------------------------------------------------
    def efetivas(self, p):
        return [x for x in p.presencas if not x.ouvinte]

    def ultima_efetiva(self, p):
        ef = self.efetivas(p)
        return max(ef, key=lambda x: x.data) if ef else None

    def efetivas_desde(self, p, desde):
        return [x for x in self.efetivas(p) if desde is None or x.data >= desde]

    def existe_na_semana(self, p, d):
        ini, fim = inicio_semana(d), fim_semana(d)
        return any(ini <= x.data <= fim for x in p.presencas)

    def tolerancia(self, p, ultima, nova):
        semanas = {inicio_semana(c) for c in CANCELADAS
                   if ultima < c < nova and (p.dia is None or WEEKDAY[p.dia] == c.weekday())}
        return TOLERANCIA + 7 * len(semanas)

    def expirado(self, p, d):
        u = self.ultima_efetiva(p)
        return p.status == "INCOMPLETO_POR_TEMPO" or (u is not None and (d - u.data).days >= self.tolerancia(p, u.data, d))

    # --- gravações -----------------------------------------------------------------------------
    def _presenca(self, p, d, ouvinte, numero):
        regra(d in self.sessoes and not self.sessoes[d]["cancelada"], f"presença fora de sessão aberta em {d}")
        regra(not any(x.data == d for x in p.presencas), f"{p.nome} já tem linha em {d}")
        pr = Presenca(d, ouvinte, numero, self.novo_k("presenca"))
        p.presencas.append(pr)
        return pr

    def _validar_dia(self, p, d):
        regra(p.dia is None or WEEKDAY[p.dia] == d.weekday(), f"{p.nome}: {d} não é o dia dele ({p.dia})")

    def _encerrar_ciclo(self, p, d, status):
        if p.ciclo is None:
            return
        p.cartoes.append({"k": self.novo_k("cartao"), "tratamento": p.tratamento, "iniciado": p.ciclo,
                          "encerrado": d, "status": status, "efetivas": len(self.efetivas_desde(p, p.ciclo))})

    def registrar_sessao(self, p, d, confirmar_reinicio=False):
        """TratamentoService.registrarSessao. Devolve 'efetiva', 'ouvinte', 'reiniciado',
        'novo_p2_apos_alta' ou 'expirado' (CartaoExpiradoException: nada gravado além do status)."""
        regra(d not in CANCELADAS, "sessão cancelada")
        if p.status == "ALTA":
            if not confirmar_reinicio or self.existe_na_semana(p, d):
                self._presenca(p, d, True, None)
                return "ouvinte"
            self._validar_dia(p, d)
            p.tratamento, p.ciclo, p.status = "P2", d, "EM_TRATAMENTO"
            self._presenca(p, d, False, 1)
            return "novo_p2_apos_alta"
        self._validar_dia(p, d)
        regra(p.status in ("EM_TRATAMENTO", "INCOMPLETO_POR_TEMPO"), f"{p.nome}: cartão {p.status} recebendo presença")
        if self.existe_na_semana(p, d):
            self._presenca(p, d, True, None)
            return "ouvinte"
        ultima = self.ultima_efetiva(p)
        reiniciado = False
        if self.expirado(p, d) and ultima is not None:
            if not confirmar_reinicio:
                p.status = "INCOMPLETO_POR_TEMPO"
                return "expirado"
            self._encerrar_ciclo(p, d, "INCOMPLETO_POR_TEMPO")
            p.tratamento, p.ciclo, p.status = "P2", d, "EM_TRATAMENTO"
            reiniciado = True
        elif ultima is None:
            p.ciclo = d
            if p.tratamento is None:
                p.tratamento = "P2"
        total = len(self.efetivas_desde(p, p.ciclo))
        entrevistas = len([e for e in p.entrevistas if e["data"] >= p.ciclo])
        regra(not (total > 0 and total % 4 == 0 and entrevistas < total // 4),
              f"{p.nome}: AvaliacaoPendenteException em {d}")
        numero = total + 1
        self._presenca(p, d, False, numero)
        if numero >= SESSOES_POR_AVALIACAO:
            p.status = "AGUARDANDO_AVALIACAO"
        return "reiniciado" if reiniciado else "efetiva"

    def registrar_ouvinte(self, p, d):
        """CheckinService.registrarOuvinte: decisão da recepção, sem passar pelo TratamentoService."""
        regra(p.ativo and p.vinculo != "CONVIDADO", "cartão não utilizável")
        self._presenca(p, d, True, None)

    def iniciar_tratamento_inicial(self, p, tratamento, d):
        """TratamentoService.iniciarTratamentoInicial (cadastro completo e cadastro rápido)."""
        regra(d not in CANCELADAS, "1ª sessão em sessão cancelada")
        self._validar_dia(p, d)
        p.tratamento, p.status, p.ciclo = tratamento, "EM_TRATAMENTO", d
        self._presenca(p, d, False, 1)

    def definir_tratamento(self, p, novo, d):
        """TratamentoService.definirTratamento com tratamento novo ('Alterar Tratamento')."""
        regra(novo != p.tratamento, "definirTratamento sem mudança")
        regra(p.status not in ("AGUARDANDO_AVALIACAO", "AGUARDANDO_ENTREVISTA"), "troca com cartão retido")
        regra(d not in CANCELADAS, "sessão cancelada")
        self._validar_dia(p, d)
        self._encerrar_ciclo(p, d, "INTERROMPIDO")
        p.tratamento, p.status, p.ciclo = novo, "EM_TRATAMENTO", d
        self._presenca(p, d, False, 1)

    def alterar_dia(self, p, novo, quando, motivo):
        """TratamentoService.alterarDiaFrequencia (grava o histórico)."""
        if p.dia == novo:
            return False
        p.historico_dia.append({"k": self.novo_k("historico"), "anterior": p.dia, "novo": novo,
                                "quando": quando, "motivo": motivo})
        p.dia = novo
        return True

    def registrar_avaliacao(self, p, d, avaliador, resultado, proposto, evolucao, recs, historico, obs):
        regra(avaliador is not p, "ninguém avalia a si mesmo")
        regra(p.status == "AGUARDANDO_AVALIACAO", f"{p.nome}: avaliação com cartão {p.status}")
        regra(d <= FIM_AVALIACOES, "avaliação futura")
        u = self.ultima_efetiva(p)
        regra(u is None or d >= u.data, "avaliação anterior à última presença")
        regra(u is None or u.numero != SESSOES_POR_AVALIACAO or d > u.data,
              "regra da casa: a avaliação nunca é no dia da 4ª presença")
        vez = len(p.avaliacoes) + 1
        if resultado == "ALTA":
            proposto = None
        regra(resultado == "ALTA" or proposto is not None, "sem tratamento proposto")
        regra(vez == 1 or evolucao is not None, "evolução obrigatória da 2ª VEZ")
        av = {"k": self.novo_k("avaliacao"), "data": d, "vez": vez, "historico": historico, "obs": obs,
              "evolucao": evolucao if vez > 1 else None, "resultado": resultado, "proposto": proposto,
              "recs": recs, "avaliador": avaliador, "entrevista": None}
        p.avaliacoes.append(av)
        p.status = "AGUARDANDO_ENTREVISTA"
        return av

    def registrar_entrevista_avulsa(self, p, tipo, d, entrevistador, obs):
        """TratamentoService.registrarEntrevistaAvulsa (V43): não mexe no cartão."""
        regra(tipo in ("PRIMEIRA_SESSAO", "EXCEPCIONAL"), "tipo de entrevista avulsa")
        regra(entrevistador is not p, "ninguém se entrevista")
        regra(d <= FIM_AVALIACOES, "entrevista futura")
        regra(bool(obs and obs.strip()), "entrevista sem o que foi conversado")
        if tipo == "PRIMEIRA_SESSAO":
            regra(any(x.data == d and not x.ouvinte and x.numero == 1 for x in p.presencas),
                  "entrevista da 1ª sessão fora da data em que um tratamento começou")
            regra(not any(e["data"] == d for e in p.entrevistas), "dia aberto pela entrevista do tratamento")
            regra(not any(e["data"] == d and e["tipo"] == "PRIMEIRA_SESSAO" for e in p.avulsas),
                  "entrevista da 1ª sessão em dobro")
        en = {"k": self.novo_k("entrevista"), "tipo": tipo, "data": d, "entrevistador": entrevistador, "obs": obs}
        p.avulsas.append(en)
        return en

    def registrar_entrevista(self, p, av, d, entrevistador, resultado, tratamento, obs):
        regra(entrevistador is not p, "ninguém se entrevista")
        regra(av in p.avaliacoes and av["entrevista"] is None, "avaliação inválida")
        regra(p.status == "AGUARDANDO_ENTREVISTA", f"{p.nome}: entrevista com cartão {p.status}")
        regra(d >= av["data"], "entrevista antes da avaliação")
        regra(not any(x.data == d and not x.ouvinte and x.numero == SESSOES_POR_AVALIACAO for x in p.presencas),
              "regra da casa: a entrevista nunca é no dia da 4ª presença")
        regra(d not in CANCELADAS, "entrevista em sessão cancelada")
        self._validar_dia(p, d)
        if resultado == "ALTA":
            tratamento = None
        regra(resultado == "ALTA" or tratamento is not None, "entrevista sem tratamento")
        en = {"k": self.novo_k("entrevista"), "avaliacao": av, "data": d, "entrevistador": entrevistador,
              "resultado": resultado, "tratamento": tratamento, "obs": obs}
        av["entrevista"] = en
        p.entrevistas.append(en)
        if resultado == "ALTA":
            self._encerrar_ciclo(p, d, "ALTA")
            p.tratamento, p.ciclo, p.status = None, None, "ALTA"
            return en
        self._encerrar_ciclo(p, d, "CONCLUIDO")
        p.tratamento, p.status, p.ciclo = tratamento, "EM_TRATAMENTO", d
        do_dia = [x for x in p.presencas if x.data == d]
        if any(not x.ouvinte for x in do_dia):
            return en
        if do_dia:
            do_dia[0].ouvinte, do_dia[0].numero = False, 1
            return en
        self._presenca(p, d, False, 1)
        return en


# ------------------------------------------------------------------------------------------------
# Simulação
# ------------------------------------------------------------------------------------------------
class Simulacao:
    def __init__(self, semente):
        self.rnd = random.Random(semente)
        self.casa = Casa(self.rnd)
        self.nomes_usados = set()
        self.k_pessoa = 0
        self.equipes = {DOMINGO: [], TERCA: []}
        self.convidados = []
        self.log = []
        self.trocas_de_dia = {}       # data -> [(pessoa, novo_dia)]
        self.trocas_manuais = 0

    # --- cadastro ------------------------------------------------------------------------------
    def nome_novo(self, sexo):
        while True:
            primeiro = self.rnd.choice(NOMES_F if sexo == "F" else NOMES_M)
            meio = self.rnd.choice(SOBRENOMES)
            ultimo = self.rnd.choice(SOBRENOMES)
            if meio == ultimo:
                continue
            nome = f"{primeiro} {meio} {ultimo}" if self.rnd.random() < 0.8 else f"{primeiro} {ultimo}"
            if nome not in self.nomes_usados:
                self.nomes_usados.add(nome)
                return nome

    def nova_pessoa(self, dia, idade_min=18, idade_max=82, sexo=None):
        sexo = sexo or ("F" if self.rnd.random() < 0.62 else "M")
        self.k_pessoa += 1
        idade = self.rnd.randint(idade_min, idade_max)
        nasc = date(2026 - idade, self.rnd.randint(1, 12), self.rnd.randint(1, 28))
        p = Pessoa(self.k_pessoa, self.nome_novo(sexo), sexo, nasc)
        p.dia = None
        p.assiduidade = self.rnd.uniform(0.80, 0.97)
        p.perfil = self.rnd.choices(["fisico", "emocional", "espiritual", "familia", "vicio"],
                                    weights=[22, 30, 26, 16, 6])[0]
        if idade < 13:
            p.perfil = "infantil"
        p.frequenta_apos_alta = self.rnd.random() < 0.25
        p.dados = self.dados_cadastrais(p)
        p.dados["dia_planejado"] = dia
        self.casa.pessoas.append(p)
        return p

    def dados_cadastrais(self, p):
        d = {"estado_civil": None, "email": None, "cep": None, "endereco": None, "numero": None,
             "complemento": None, "bairro": None, "cidade": None, "uf": None, "residencia": None,
             "rapido": False}
        if p.idade(INICIO) >= 18:
            d["estado_civil"] = self.rnd.choice(ESTADOS_CIVIS)
        if p.idade(INICIO) >= 16 and self.rnd.random() < 0.45:
            base = p.nome.lower().split()
            usuario = f"{base[0]}.{base[-1]}.{p.k}"
            usuario = unicodedata.normalize("NFKD", usuario).encode("ascii", "ignore").decode()
            d["email"] = f"{usuario}@sim.invalid"
        bairro = self.rnd.choice(BAIRROS)
        d.update(cep=f"0{self.rnd.randint(4000, 4999)}-{self.rnd.randint(0, 999):03d}",
                 endereco=self.rnd.choice(RUAS), numero=str(self.rnd.randint(12, 1900)),
                 complemento=self.rnd.choice([None, None, None, "Apto 12", "Casa 2", "Bloco B apto 41", "Fundos"]),
                 bairro=bairro, cidade="São Paulo", uf="SP")
        d["residencia"] = f"{d['endereco']}, {d['numero']} - {bairro}, São Paulo/SP"
        return d

    def cadastrar(self, p, d, rapido=False):
        """POST /salvar (ou o cadastro rápido do painel): dia com histórico + P2 + 1ª presença."""
        regra(not p.cadastrado, "cadastro em dobro")
        p.cadastrado = True
        if rapido:
            # O cadastro rápido só pede nome, nascimento e sexo; o resto fica para a edição.
            p.dados.update(email=None, cep=None, endereco=None, numero=None, complemento=None, bairro=None,
                           cidade=None, uf=None, residencia=None, estado_civil=None, rapido=True)
        motivo = "Cadastro rápido na recepção." if rapido else "Primeira assistência informada no cadastro."
        self.casa.alterar_dia(p, p.dados["dia_planejado"], datetime.combine(d, hora_sessao(d)), motivo)
        self.casa.iniciar_tratamento_inicial(p, "P2", d)

    def montar_equipes(self):
        for dia in (DOMINGO, TERCA):
            for funcoes in EQUIPE:
                p = self.nova_pessoa(dia, 24, 74)
                p.funcoes = set(funcoes)
                p.vinculo = "TRABALHADOR"
                p.assiduidade = self.rnd.uniform(0.90, 0.98)
                p.frequenta_apos_alta = True      # o trabalhador continua vindo trabalhar
                self.equipes[dia].append(p)
        for nome, tel, origem, email in CONVIDADOS:
            self.k_pessoa += 1
            c = Pessoa(self.k_pessoa, nome, "M" if nome.split()[0] in NOMES_M else "F", date(1965, 5, 10))
            c.vinculo, c.status, c.cadastrado = "CONVIDADO", "EM_TRATAMENTO", True
            c.dados = {"telefone": tel, "origem": origem, "email": email}
            self.nomes_usados.add(nome)
            self.casa.pessoas.append(c)
            self.convidados.append(c)

    # --- datas ---------------------------------------------------------------------------------
    def datas_de_sessao(self):
        d = INICIO
        while d <= FIM:
            if d.weekday() in (6, 1):
                yield d
            d += timedelta(days=1)

    # --- preparação das sessões (preleção e registro da sessão) --------------------------------
    def preparar_sessoes(self):
        temas = TEMAS[:]
        self.rnd.shuffle(temas)
        idx_conv = 0
        for i, d in enumerate(self.datas_de_sessao()):
            dia = DOMINGO if d.weekday() == 6 else TERCA
            cancelada = d in CANCELADAS
            self.casa.sessoes[d] = {"data": d, "dia": dia, "cancelada": cancelada, "escala": [],
                                    "substituto": None, "tema_substituto": None}
            expositores = [w for w in self.equipes[dia] if "EXPOSITOR_PRELETOR" in w.funcoes]
            pre = {"data": d, "tema": temas[i % len(temas)], "trabalhador": None, "convidado": None,
                   "cancelada": None}
            if self.rnd.random() < 0.15:
                pre["convidado"] = self.convidados[idx_conv % len(self.convidados)]
                idx_conv += 1
            else:
                pre["trabalhador"] = expositores[i % len(expositores)]
            if cancelada:
                pre["cancelada"] = CANCELADAS[d]
            self.casa.prelecoes[d] = pre

    # --- o dia da sessão -----------------------------------------------------------------------
    def simular(self):
        self.montar_equipes()
        self.preparar_sessoes()
        self.planejar_trocas_de_dia()
        ativos = {DOMINGO: [], TERCA: []}
        # Quem já frequentava a casa quando o prontuário digital começou: cadastrado no 1º dia dele.
        for dia in (DOMINGO, TERCA):
            for _ in range(30):
                ativos[dia].append(self.nova_pessoa(dia))
            # algumas crianças, que vêm com a família
            for _ in range(2):
                ativos[dia].append(self.nova_pessoa(dia, 6, 12))

        dia_corrente = INICIO
        while dia_corrente <= FIM_AVALIACOES:
            self.avaliacoes_marcadas_para(dia_corrente)
            if dia_corrente <= FIM and dia_corrente.weekday() in (6, 1):
                self.sessao(dia_corrente, ativos)
            dia_corrente += timedelta(days=1)

    def planejar_trocas_de_dia(self):
        # Algumas pessoas trocam de dia ao longo do semestre ("Mudar para <dia> e carimbar").
        datas = [d for d in self.datas_de_sessao() if d not in CANCELADAS and d > INICIO + timedelta(days=40)]
        self.rnd.shuffle(datas)
        self.datas_troca = sorted(datas[:6])

    def presentes_do_dia(self, d, dia, ativos):
        """Quem vem hoje (antes do controle de lotação)."""
        vem = []
        for w in self.equipes[dia]:
            if w.ausente_ate and d <= w.ausente_ate:
                continue
            if self.rnd.random() < w.assiduidade:
                vem.append(w)
            elif self.rnd.random() < 0.01:
                w.ausente_ate = d + timedelta(days=self.rnd.choice([21, 28]))
        for p in ativos[dia]:
            if p.saiu or not p.ativo or p.trabalhador:
                continue
            if p.dia not in (None, dia) and p.dia != dia:
                continue
            if p.ausente_ate and d <= p.ausente_ate:
                continue
            if p.status == "ALTA":
                if p.volta_apos_alta_em and d >= p.volta_apos_alta_em:
                    vem.append(p)
                elif p.frequenta_apos_alta and self.rnd.random() < 0.45:
                    vem.append(p)
                continue
            r = self.rnd.random()
            if r < 0.012 and p.cadastrado:
                p.saiu = True                  # parou de vir, sem avisar
                continue
            if r < 0.035 and p.cadastrado:
                p.ausente_ate = d + timedelta(days=self.rnd.choice([21, 28, 35, 42]))
                continue
            if self.rnd.random() < p.assiduidade or not p.cadastrado:
                vem.append(p)
        return vem

    def sessao(self, d, ativos):
        dia = DOMINGO if d.weekday() == 6 else TERCA
        s = self.casa.sessoes[d]
        presentes = self.presentes_do_dia(d, dia, ativos)
        trabalhadores = [p for p in presentes if p.trabalhador]
        self.montar_escala(s, dia, trabalhadores)
        if s["cancelada"]:
            return

        # Visitantes de outro dia (acompanham alguém da família): só ouvinte, pela recepção.
        outro = TERCA if dia == DOMINGO else DOMINGO
        visitantes = [p for p in ativos[outro] if p.cadastrado and p.ativo and not p.saiu and not p.trabalhador
                      and p.status != "ALTA" and p not in presentes]
        self.rnd.shuffle(visitantes)
        visitantes = visitantes[:self.rnd.choice([0, 0, 1, 1, 2])]

        # Trocas de dia planejadas para esta data.
        trocas = []
        if d in self.datas_troca:
            candidatos = [p for p in ativos[outro] if p.cadastrado and p.ativo and not p.saiu and not p.trabalhador
                          and p.status in ("EM_TRATAMENTO",) and p not in presentes and p not in visitantes
                          and not self.casa.expirado(p, d)]
            if candidatos:
                trocas.append(self.rnd.choice(candidatos))

        # Controle de lotação: 45 a 50 pessoas na sessão.
        total = len(presentes) + len(visitantes) + len(trocas)
        alvo = self.rnd.randint(MIN_POR_SESSAO + 1, MAX_POR_SESSAO - 1)
        novos = []
        if total < alvo:
            n_novos = alvo - total
        else:
            n_novos = self.rnd.choice([0, 0, 1])
        while total + n_novos > MAX_POR_SESSAO:
            removiveis = [p for p in presentes if not p.trabalhador and p.cadastrado and p.status == "EM_TRATAMENTO"]
            if not removiveis:
                if n_novos > 0:
                    n_novos -= 1
                elif visitantes:
                    visitantes.pop()
                    total = len(presentes) + len(visitantes) + len(trocas)
                else:
                    break
                continue
            presentes.remove(self.rnd.choice(removiveis))   # faltou nesta semana
            total = len(presentes) + len(visitantes) + len(trocas)
        for _ in range(n_novos):
            crianca = self.rnd.random() < 0.05
            novo = self.nova_pessoa(dia, 6 if crianca else 16, 12 if crianca else 82)
            ativos[dia].append(novo)
            novos.append(novo)

        ordem = presentes + visitantes + trocas + novos
        self.rnd.shuffle(ordem)
        for p in ordem:
            if p in novos or not p.cadastrado:
                self.cadastrar(p, d, rapido=not p.trabalhador and self.rnd.random() < 0.55)
            elif p in visitantes:
                self.casa.registrar_ouvinte(p, d)
            elif p in trocas:
                self.casa.alterar_dia(p, dia, datetime.combine(d, hora_sessao(d)),
                                      self.rnd.choice(["Mudou de turno no trabalho.", "Passou a vir com a família no outro dia.",
                                                       "Mudança de endereço; o outro horário ficou mais fácil."]))
                ativos[outro].remove(p)
                ativos[dia].append(p)
                self.atender(p, d, s)
            else:
                self.atender(p, d, s)
        # quem chega com o cartão retido passa pela avaliação / entrevista no fim da sessão
        for p in ordem:
            self.pos_sessao(p, d, s)
        # Entrevista excepcional: alguém pede para conversar, sem ligação com o tratamento. Quase sempre
        # no próprio dia; às vezes o entrevistador o recebe num dia da semana (data livre).
        if self.rnd.random() < 0.45:
            pedem = [p for p in ordem if any(x.data == d for x in p.presencas)]
            if pedem:
                p = self.rnd.choice(pedem)
                entrevistador = self.escolher_entrevistador(p, d, s)
                quando = d if self.rnd.random() < 0.7 else d + timedelta(days=self.rnd.randint(1, 3))
                if entrevistador and quando <= FIM_AVALIACOES:
                    self.casa.registrar_entrevista_avulsa(p, "EXCEPCIONAL", quando, entrevistador,
                                                          self.rnd.choice(OBS_EXCEPCIONAL))

    def atender(self, p, d, s):
        casa = self.casa
        if p.status == "ALTA":
            confirmar = p.volta_apos_alta_em is not None and d >= p.volta_apos_alta_em
            r = casa.registrar_sessao(p, d, confirmar)
            if r == "novo_p2_apos_alta":
                p.volta_apos_alta_em = None
            return
        if p.status in ("AGUARDANDO_AVALIACAO", "AGUARDANDO_ENTREVISTA"):
            casa.registrar_ouvinte(p, d)      # recepção: cartão retido, entra como ouvinte e é encaminhado
            return
        if p.status == "INCOMPLETO_POR_TEMPO":
            casa.registrar_sessao(p, d, True)  # "Reiniciar em P2 e carimbar"
            return
        # Em tratamento: troca manual de tratamento pelo Dirigente (rara), ou check-in normal.
        if (not casa.existe_na_semana(p, d) and not casa.expirado(p, d) and p.ciclo is not None
                and len(casa.efetivas_desde(p, p.ciclo)) in (1, 2) and self.rnd.random() < 0.006
                and self.trocas_manuais < 5):
            novo = self.escolher_tratamento(p, d, evitar=p.tratamento)
            casa.definir_tratamento(p, novo, d)
            self.trocas_manuais += 1
            return
        if casa.expirado(p, d):
            if self.rnd.random() < (0.5 if d >= FIM - timedelta(days=13) else 0.25):
                # Leu o QR da sessão pelo celular: o cartão expira e ele é mandado à recepção. Nas duas
                # últimas semanas, quem passa por isso vai embora sem procurar a recepção (fica
                # "Incompleto por Tempo"); antes, a maioria procura e a recepção reinicia em P2. O
                # trabalhador sempre procura: ele está na escala do dia.
                if casa.registrar_sessao(p, d, False) == "expirado":
                    if p.trabalhador or (self.rnd.random() < 0.55 and d < FIM - timedelta(days=9)):
                        casa.registrar_sessao(p, d, True)
                return
            casa.registrar_sessao(p, d, True)
            return
        casa.registrar_sessao(p, d, False)

    def pos_sessao(self, p, d, s):
        casa = self.casa
        if p.status == "AGUARDANDO_AVALIACAO" and p.avaliacao_marcada is None:
            presencas_hoje = [x for x in p.presencas if x.data == d]
            completou_hoje = any(not x.ouvinte and x.numero == SESSOES_POR_AVALIACAO for x in presencas_hoje)
            r = self.rnd.random()
            # Nas duas últimas semanas a fila do Avaliador anda mais devagar: é o retrato que o
            # sistema mostra hoje, com cartões Em Avaliação e Aguardando Entrevista.
            ultimas_semanas = d >= FIM - timedelta(days=13)
            if completou_hoje:
                # Regra da casa: a avaliação nunca é no dia da 4ª presença. O Avaliador atende nos dias
                # seguintes (a data da avaliação é livre) ou na próxima vez que a pessoa vier.
                if r < (0.40 if ultimas_semanas else 0.75):
                    marcada = d + timedelta(days=self.rnd.randint(1, 5))
                    if marcada <= FIM_AVALIACOES:
                        p.avaliacao_marcada = marcada
                # senão: fica para o próximo dia em que vier
            elif presencas_hoje:
                if r < 0.9:
                    self.avaliar(p, d, s)
        if (p.status == "AGUARDANDO_ENTREVISTA" and WEEKDAY[p.dia] == d.weekday()
                and any(x.data == d for x in p.presencas)):
            av = p.avaliacoes[-1]
            mesmo_dia = av["data"] == d
            # Regra da casa: a entrevista nunca acontece no dia da 4ª presença. Só entrevista no dia
            # quem entrou como ouvinte, com o cartão retido.
            if any(x.data == d and not x.ouvinte for x in p.presencas):
                return
            if (mesmo_dia and self.rnd.random() < 0.15) or (not mesmo_dia and self.rnd.random() < 0.88):
                self.entrevistar(p, av, d, s)
        # Entrevista da 1ª sessão, se o assistido quiser: no dia em que um tratamento começou (cadastro,
        # reinício em P2, volta depois da alta, troca de tratamento) — não no dia em que a entrevista do
        # tratamento abriu o ciclo, que já foi a conversa daquela 1ª sessão.
        if (any(x.data == d and not x.ouvinte and x.numero == 1 for x in p.presencas)
                and not any(e["data"] == d for e in p.entrevistas)
                and not any(e["data"] == d for e in p.avulsas)):
            novato = len(self.casa.efetivas(p)) == 1
            if self.rnd.random() < (0.6 if novato else 0.3):
                entrevistador = self.escolher_entrevistador(p, d, s)
                if entrevistador:
                    self.casa.registrar_entrevista_avulsa(p, "PRIMEIRA_SESSAO", d, entrevistador,
                                                          self.rnd.choice(OBS_PRIMEIRA_SESSAO))

    def avaliacoes_marcadas_para(self, d):
        for p in self.casa.pessoas:
            if p.avaliacao_marcada == d:
                p.avaliacao_marcada = None
                if p.status == "AGUARDANDO_AVALIACAO":
                    equipe = self.equipes[p.dia]
                    avaliadores = [w for w in equipe if w is not p and ({"AVALIADOR", "DIRIGENTE"} & w.funcoes)]
                    self.avaliar(p, d, None, self.rnd.choice(avaliadores))

    # --- atendimento ---------------------------------------------------------------------------
    def escolher_tratamento(self, p, d, evitar=None):
        if p.idade(d) < 13:
            return self.rnd.choice([t for t in ["P4", "P4", "P4", "P2"] if t != evitar])
        pesos = dict(TRATAMENTOS_ADULTO)
        preferidos = {"fisico": ["P1", "P3A (ou P3F)"], "emocional": ["P3C", "A2", "CH"],
                      "espiritual": ["P3E", "P3B", "CH"], "familia": ["A2", "P1"],
                      "vicio": ["P3DC / P3V", "P3E"]}.get(p.perfil, [])
        for t in preferidos:
            pesos[t] *= 3
        if evitar in pesos:
            del pesos[evitar]
        return self.rnd.choices(list(pesos), weights=list(pesos.values()))[0]

    def avaliar(self, p, d, s, avaliador=None):
        if avaliador is None:
            presentes = [w for w in self.equipes[p.dia] if any(x.data == d for x in w.presencas)]
            candidatos = [w for w in presentes if w is not p and "AVALIADOR" in w.funcoes] or \
                         [w for w in presentes if w is not p and "DIRIGENTE" in w.funcoes]
            if not candidatos:
                return
            avaliador = self.rnd.choice(candidatos)
        vez = len(p.avaliacoes) + 1
        evolucao = None
        if vez > 1:
            evolucao = self.rnd.choices(["MELHOR", "BOM", "INDIFERENTE", "PIOR"], weights=[45, 25, 20, 10])[0]
        chance_alta = {1: 0.02, 2: 0.08, 3: 0.16, 4: 0.26}.get(vez, 0.35)
        if p.trabalhador:
            chance_alta += 0.05
        if evolucao == "PIOR":
            chance_alta = 0.0
        resultado = "ALTA" if self.rnd.random() < chance_alta else "NOVO_TRATAMENTO"
        proposto = None
        if resultado == "NOVO_TRATAMENTO":
            proposto = p.tratamento if (p.tratamento and p.tratamento != "P2" and self.rnd.random() < 0.35) \
                else self.escolher_tratamento(p, d)
        recs = {
            "visto": self.rnd.random() < 0.3, "assistencia": self.rnd.random() < 0.4,
            "evangelho_no_lar": self.rnd.random() < 0.72, "leituras": self.rnd.random() < 0.5,
            "escola": self.rnd.random() < 0.15, "trabalho_espiritual": self.rnd.random() < (0.05 + 0.04 * vez),
            "medico": self.rnd.random() < (0.35 if p.perfil == "fisico" else 0.1),
        }
        queixa = self.rnd.choice(QUEIXAS[p.perfil])
        historico = self.rnd.choice(HISTORICOS).format(queixa=queixa, contexto=self.rnd.choice(CONTEXTOS),
                                                       contexto_min=self.rnd.choice(CONTEXTOS_MIN))
        obs = " ".join(self.rnd.sample(OBS_AVALIACAO, self.rnd.choice([1, 2])))
        self.casa.registrar_avaliacao(p, d, avaliador, resultado, proposto, evolucao, recs, historico, obs)

    def escolher_entrevistador(self, p, d, s):
        escalados = [e["trabalhador"] for e in s["escala"] if e["posicao"] == "ENTREVISTADOR"]
        equipe = DOMINGO if d.weekday() == 6 else TERCA
        presentes = [w for w in self.equipes[equipe] if any(x.data == d for x in w.presencas)]
        candidatos = [w for w in escalados if w is not p] or \
                     [w for w in presentes if w is not p and ({"ENTREVISTADOR", "DIRIGENTE"} & w.funcoes)]
        return self.rnd.choice(candidatos) if candidatos else None

    def entrevistar(self, p, av, d, s):
        entrevistador = self.escolher_entrevistador(p, d, s)
        if entrevistador is None:
            return
        resultado, tratamento = av["resultado"], av["proposto"]
        r = self.rnd.random()
        if resultado == "ALTA" and r < 0.12:
            resultado, tratamento = "NOVO_TRATAMENTO", self.escolher_tratamento(p, d)   # entrevistador ajustou
        elif resultado == "NOVO_TRATAMENTO" and r < 0.08:
            tratamento = self.escolher_tratamento(p, d, evitar=tratamento)
        obs = self.rnd.choice(OBS_ALTA if resultado == "ALTA" else OBS_ENTREVISTA)
        self.casa.registrar_entrevista(p, av, d, entrevistador, resultado, tratamento, obs)
        if resultado == "ALTA" and not p.trabalhador:
            if self.rnd.random() < 0.07:
                p.volta_apos_alta_em = d + timedelta(days=self.rnd.choice([35, 49, 63]))
            elif not p.frequenta_apos_alta:
                p.saiu = True

    # --- escala --------------------------------------------------------------------------------
    def montar_escala(self, s, dia, trabalhadores):
        usados = set()
        livres = trabalhadores[:]
        self.rnd.shuffle(livres)
        for posicao, minimo, funcoes in POSICOES:
            quantos = minimo
            if posicao in ("CAMARA_PASSE", "RECEPCIONISTA") and self.rnd.random() < 0.3:
                quantos += 1
            escolhidos = []
            for preferencia in (lambda w: bool(set(funcoes) & w.funcoes) and id(w) not in usados,
                                lambda w: id(w) not in usados,
                                lambda w: True):
                for w in livres:
                    if len(escolhidos) >= quantos:
                        break
                    if w not in escolhidos and preferencia(w):
                        escolhidos.append(w)
                if len(escolhidos) >= quantos:
                    break
            regra(len(escolhidos) >= minimo, f"escala sem o mínimo de {posicao} em {s['data']}")
            for w in escolhidos:
                usados.add(id(w))
                s["escala"].append({"trabalhador": w, "posicao": posicao})
        # Troca emergencial do preletor quando o expositor da escala não veio.
        pre = self.casa.prelecoes[s["data"]]
        if not s["cancelada"] and pre["trabalhador"] is not None and pre["trabalhador"] not in trabalhadores:
            outros = [w for w in trabalhadores if "EXPOSITOR_PRELETOR" in w.funcoes]
            if outros:
                s["substituto"] = self.rnd.choice(outros)
                s["tema_substituto"] = self.rnd.choice(TEMAS)


def hora_sessao(d):
    from datetime import time
    return time(8, 0) if d.weekday() == 6 else time(19, 0)


# ------------------------------------------------------------------------------------------------
# SQL
# ------------------------------------------------------------------------------------------------
def q(v):
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, datetime):
        return f"TIMESTAMP '{v:%Y-%m-%d %H:%M:%S}'"
    if isinstance(v, date):
        return f"DATE '{v:%Y-%m-%d}'"
    return "'" + str(v).replace("'", "''") + "'"


def sid(tabela, k):
    return f"pg_temp.sid('{tabela}', {k})"


def tt(codigo):
    return "NULL" if codigo is None else f"pg_temp.tt({q(codigo)})"


def insert(out, tabela, colunas, linhas, lote=400):
    for i in range(0, len(linhas), lote):
        out.append(f"INSERT INTO {tabela} ({', '.join(colunas)}) VALUES")
        out.append(",\n".join("  (" + ", ".join(l) + ")" for l in linhas[i:i + lote]) + ";")


def gerar_sql(sim: Simulacao):
    casa = sim.casa
    pessoas = [p for p in casa.pessoas if p.cadastrado]
    out = []
    w = out.append
    w("-- =============================================================================================")
    w("-- Carga de SIMULAÇÃO: seis meses de funcionamento fictício da casa (05/04/2026 a 29/09/2026).")
    w("-- GERADO por scripts/simulacao/gerar_simulacao.py — não editar à mão; gere de novo.")
    w("-- Tudo o que esta carga grava fica listado em simulacao_registro, e remover-simulacao.sql apaga")
    w("-- exatamente isso. Roda numa transação só: ou entra tudo, ou nada.")
    w("-- =============================================================================================")
    w("\\set ON_ERROR_STOP on")
    w("SET client_encoding = 'UTF8';")
    w("BEGIN;")
    w("")
    w("DO $$")
    w("BEGIN")
    w("  IF to_regclass('public.simulacao_registro') IS NOT NULL THEN")
    w("    RAISE EXCEPTION 'A simulação já está carregada neste banco (tabela simulacao_registro existe). Rode remover-simulacao.sql antes de carregar de novo.';")
    w("  END IF;")
    w(f"  IF EXISTS (SELECT 1 FROM sessao_assistencia WHERE data BETWEEN {q(INICIO)} AND {q(FIM_AVALIACOES)})")
    w(f"     OR EXISTS (SELECT 1 FROM sessao_tratamento WHERE data_consulta BETWEEN {q(INICIO)} AND {q(FIM_AVALIACOES)})")
    w(f"     OR EXISTS (SELECT 1 FROM prelecao WHERE data_apresentacao BETWEEN {q(INICIO)} AND {q(FIM_AVALIACOES)})")
    w(f"     OR EXISTS (SELECT 1 FROM avaliacao WHERE data BETWEEN {q(INICIO)} AND {q(FIM_AVALIACOES)})")
    w(f"     OR EXISTS (SELECT 1 FROM entrevista WHERE data BETWEEN {q(INICIO)} AND {q(FIM_AVALIACOES)}) THEN")
    w("    RAISE EXCEPTION 'Já existem sessões, presenças, preleções, avaliações ou entrevistas entre 05/04/2026 e 02/10/2026. A simulação não mistura dados com um período que já tem registros reais.';")
    w("  END IF;")
    w("  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'entrevista' AND column_name = 'tipo') THEN")
    w("    RAISE EXCEPTION 'Este banco ainda não tem a migration V43 (tipos de entrevista). Publique a versão do sistema que a traz e rode a carga depois.';")
    w("  END IF;")
    w("  IF (SELECT count(*) FROM tipo_tratamento WHERE codigo IN ('P1','P2','P3A (ou P3F)','P3C','P3E','P3DC / P3V','A2','CH','P4','P3B')) <> 10 THEN")
    w("    RAISE EXCEPTION 'O catálogo de tratamentos não tem os 10 códigos esperados (V3 + V31).';")
    w("  END IF;")
    w("  IF EXISTS (SELECT 1 FROM assistido WHERE lower(email) LIKE '%@sim.invalid') THEN")
    w("    RAISE EXCEPTION 'Já há cadastros com e-mail @sim.invalid neste banco.';")
    w("  END IF;")
    w("END $$;")
    w("")
    w("CREATE TABLE simulacao_registro (")
    w("    tabela      VARCHAR(40) NOT NULL,")
    w("    registro_id BIGINT      NOT NULL,")
    w("    carregado_em TIMESTAMP  NOT NULL DEFAULT now(),")
    w("    PRIMARY KEY (tabela, registro_id)")
    w(");")
    w("COMMENT ON TABLE simulacao_registro IS 'Linhas gravadas pela carga de simulação (scripts/simulacao). remover-simulacao.sql apaga essas linhas e esta tabela.';")
    w("")
    w("-- sid(tabela, chave): o id real de cada registro da carga, tirado da sequência da própria tabela")
    w("-- (o sistema continua numerando normalmente depois) e anotado em simulacao_registro.")
    w("CREATE TEMP TABLE sim_ids (tabela TEXT, k INT, id BIGINT, PRIMARY KEY (tabela, k)) ON COMMIT DROP;")
    w("CREATE FUNCTION pg_temp.sid(t TEXT, chave INT) RETURNS BIGINT LANGUAGE plpgsql AS $$")
    w("DECLARE novo BIGINT;")
    w("BEGIN")
    w("  SELECT id INTO novo FROM sim_ids WHERE tabela = t AND k = chave;")
    w("  IF novo IS NULL THEN")
    w("    novo := nextval(pg_get_serial_sequence(t, 'id'));")
    w("    INSERT INTO sim_ids VALUES (t, chave, novo);")
    w("    INSERT INTO simulacao_registro (tabela, registro_id) VALUES (t, novo);")
    w("  END IF;")
    w("  RETURN novo;")
    w("END $$;")
    w("CREATE FUNCTION pg_temp.tt(c TEXT) RETURNS BIGINT LANGUAGE sql STABLE AS $$ SELECT id FROM tipo_tratamento WHERE codigo = c $$;")
    w("")

    # assistido ----------------------------------------------------------------------------------
    w(f"-- {len(pessoas)} cadastros (assistidos, trabalhadores e preletores convidados)")
    cols = ["id", "nome", "residencia", "estado_civil", "sexo", "email", "vinculo", "tratamento_atual_id",
            "ciclo_iniciado_em", "data_nascimento", "dia_frequencia", "ativo", "cep", "endereco", "bairro",
            "cidade", "uf", "numero", "complemento", "status_cartao", "codigo_cartao", "telefone", "origem"]
    linhas = []
    for p in pessoas:
        dd = p.dados
        if p.vinculo == "CONVIDADO":
            linhas.append([sid("assistido", p.k), q(p.nome), "NULL", "NULL", q(p.sexo), q(dd.get("email")),
                           q("CONVIDADO"), "NULL", "NULL", "NULL", "NULL", "TRUE", "NULL", "NULL", "NULL",
                           "NULL", "NULL", "NULL", "NULL", q("EM_TRATAMENTO"), "NULL", q(dd["telefone"]),
                           q(dd["origem"])])
            continue
        linhas.append([sid("assistido", p.k), q(p.nome), q(dd["residencia"]), q(dd["estado_civil"]), q(p.sexo),
                       q(dd["email"]), q(p.vinculo), tt(p.tratamento), q(p.ciclo), q(p.nascimento), q(p.dia),
                       q(p.ativo), q(dd["cep"]), q(dd["endereco"]), q(dd["bairro"]), q(dd["cidade"]), q(dd["uf"]),
                       q(dd["numero"]), q(dd["complemento"]), q(p.status), "gen_random_uuid()::text", "NULL", "NULL"])
    insert(out, "assistido", cols, linhas)
    w("")

    # trabalhador --------------------------------------------------------------------------------
    trabalhadores = [p for p in pessoas if p.trabalhador]
    w(f"-- {len(trabalhadores)} trabalhadores (18 por dia) e as funções de cada um")
    insert(out, "trabalhador", ["id", "assistido_id"],
           [[sid("trabalhador", p.k), sid("assistido", p.k)] for p in trabalhadores])
    insert(out, "trabalhador_funcao", ["trabalhador_id", "funcao"],
           [[sid("trabalhador", p.k), q(f)] for p in trabalhadores for f in sorted(p.funcoes)])
    w("")

    # preleções ----------------------------------------------------------------------------------
    w("-- preleções (uma por sessão; as das sessões canceladas ficam canceladas)")
    linhas = []
    for i, (d, pre) in enumerate(sorted(casa.prelecoes.items()), start=1):
        pre["k"] = i
        canc = pre["cancelada"]
        linhas.append([sid("prelecao", i), q(d), q(pre["tema"]),
                       sid("trabalhador", pre["trabalhador"].k) if pre["trabalhador"] else "NULL",
                       sid("assistido", pre["convidado"].k) if pre["convidado"] else "NULL",
                       q(canc[0] if canc else None), q(canc[1] if canc else None)])
    insert(out, "prelecao", ["id", "data_apresentacao", "tema", "trabalhador_id", "convidado_id", "cancelada_em",
                             "motivo_cancelamento"], linhas)
    w("")

    # sessões ------------------------------------------------------------------------------------
    w("-- sessões de assistência: domingos 08h e terças 19h, todas encerradas (ou canceladas)")
    linhas, escala = [], []
    k_escala = 0
    for i, (d, s) in enumerate(sorted(casa.sessoes.items()), start=1):
        s["k"] = i
        domingo = d.weekday() == 6
        aberto = datetime.combine(d, (datetime(2000, 1, 1, 7, 40) if domingo else datetime(2000, 1, 1, 18, 40)).time())
        if s["cancelada"]:
            canc = CANCELADAS[d]
            linhas.append([sid("sessao_assistencia", i), q(d), q(s["dia"]), "NULL", "NULL", "NULL", "NULL",
                           q(canc[0]), q(canc[1]), "NULL", "FALSE"])
        else:
            auto = d in ENCERRADAS_AUTOMATICAMENTE
            fechado = datetime.combine(d, datetime(2000, 1, 1, 23, 59, 59).time()) if auto else \
                datetime.combine(d, (datetime(2000, 1, 1, 10, 25) if domingo else datetime(2000, 1, 1, 21, 15)).time())
            linhas.append([sid("sessao_assistencia", i), q(d), q(s["dia"]),
                           sid("trabalhador", s["substituto"].k) if s["substituto"] else "NULL",
                           q(s["tema_substituto"]), q(aberto), q(fechado), "NULL", "NULL", q(fechado), q(auto)])
        for e in s["escala"]:
            k_escala += 1
            escala.append([sid("sessao_escala", k_escala), sid("sessao_assistencia", i),
                           sid("trabalhador", e["trabalhador"].k), q(e["posicao"])])
    insert(out, "sessao_assistencia", ["id", "data", "dia_frequencia", "preletor_substituto_id", "tema_substituto",
                                       "checkin_aberto_em", "checkin_fechado_em", "cancelada_em",
                                       "motivo_cancelamento", "encerrada_em", "encerrada_automaticamente"], linhas)
    w("")
    w(f"-- escala das sessões ({len(escala)} posições preenchidas, sempre com o mínimo de cada setor)")
    insert(out, "sessao_escala", ["id", "sessao_id", "trabalhador_id", "posicao"], escala)
    w("")

    # presenças ----------------------------------------------------------------------------------
    pres = []
    for p in pessoas:
        for x in sorted(p.presencas, key=lambda x: x.data):
            pre = casa.prelecoes.get(x.data)
            pre_id = sid("prelecao", pre["k"]) if pre and not pre["cancelada"] else "NULL"
            pres.append([sid("sessao_tratamento", x.k), sid("assistido", p.k), q(x.numero), q(x.data),
                         q(x.ouvinte), pre_id])
    w(f"-- {len(pres)} presenças (efetivas e de ouvinte)")
    insert(out, "sessao_tratamento", ["id", "assistido_id", "numero_serie", "data_consulta", "ouvinte", "prelecao_id"],
           pres, lote=500)
    w("")

    # avaliações e entrevistas -------------------------------------------------------------------
    avs, ens = [], []
    for p in pessoas:
        for av in p.avaliacoes:
            r = av["recs"]
            avs.append([sid("avaliacao", av["k"]), sid("assistido", p.k), q(av["vez"]), q(av["data"]),
                        q(av["historico"]), q(av["evolucao"]), q(av["obs"]), tt(av["proposto"]), q(av["resultado"]),
                        q(r["visto"]), q(r["assistencia"]), q(r["evangelho_no_lar"]), q(r["leituras"]),
                        q(r["escola"]), q(r["trabalho_espiritual"]), q(r["medico"]), sid("assistido", av["avaliador"].k)])
            en = av["entrevista"]
            if en:
                ens.append([sid("entrevista", en["k"]), q("TRATAMENTO"), sid("avaliacao", av["k"]), sid("assistido", p.k),
                            q(en["data"]), q(en["entrevistador"].nome), tt(en["tratamento"]), q(en["resultado"]),
                            q(en["obs"]), sid("assistido", en["entrevistador"].k)])
        for en in p.avulsas:
            ens.append([sid("entrevista", en["k"]), q(en["tipo"]), "NULL", sid("assistido", p.k), q(en["data"]),
                        q(en["entrevistador"].nome), "NULL", "NULL", q(en["obs"]), sid("assistido", en["entrevistador"].k)])
    avulsas = sum(len(p.avulsas) for p in pessoas)
    w(f"-- {len(avs)} avaliações e {len(ens)} entrevistas ({len(ens) - avulsas} de tratamento, {avulsas} da 1ª sessão ou excepcionais)")
    insert(out, "avaliacao", ["id", "assistido_id", "numero_vez", "data", "historico", "evolucao", "observacoes",
                              "tratamento_proposto_id", "resultado", "rec_visto", "rec_assistencia",
                              "rec_evangelho_no_lar", "rec_leituras", "rec_escola", "rec_trabalho_espiritual",
                              "rec_medico", "avaliador_id"], avs)
    insert(out, "entrevista", ["id", "tipo", "avaliacao_id", "assistido_id", "data", "entrevistador", "tratamento_indicado_id",
                               "resultado", "observacoes", "entrevistador_id"], ens)
    w("")

    # histórico de cartões e de dia --------------------------------------------------------------
    cartoes = [[sid("cartao_encerrado", c["k"]), sid("assistido", p.k), tt(c["tratamento"]), q(c["iniciado"]),
                q(c["encerrado"]), q(c["status"]), q(c["efetivas"])] for p in pessoas for c in p.cartoes]
    w(f"-- {len(cartoes)} cartões encerrados (histórico de tratamentos)")
    insert(out, "cartao_encerrado", ["id", "assistido_id", "tratamento_id", "iniciado_em", "encerrado_em",
                                     "status_final", "sessoes_efetivas"], cartoes)
    hist = [[sid("historico_dia_frequencia", h["k"]), sid("assistido", p.k), q(h["anterior"]), q(h["novo"]),
             q(h["quando"]), q(h["motivo"])] for p in pessoas for h in p.historico_dia]
    w(f"-- {len(hist)} registros de dia de assistência")
    insert(out, "historico_dia_frequencia", ["id", "assistido_id", "dia_anterior", "dia_novo", "data_hora", "motivo"],
           hist)
    w("")
    w("SELECT tabela, count(*) AS registros FROM simulacao_registro GROUP BY tabela ORDER BY tabela;")
    w("COMMIT;")
    return "\n".join(out) + "\n"


def desativar_alguns(sim):
    # Quem parou de vir e avisou que mudou de cidade: a recepção desativa o cadastro (exclusão lógica).
    saidos = [p for p in sim.casa.pessoas if p.saiu and p.cadastrado and not p.trabalhador and p.vinculo == "ASSISTIDO"]
    sim.rnd.shuffle(saidos)
    for p in saidos[:4]:
        p.ativo = False


def resumo(sim):
    from collections import Counter
    pessoas = [p for p in sim.casa.pessoas if p.cadastrado and p.vinculo != "CONVIDADO"]
    st = Counter(p.status for p in pessoas)
    por_sessao = []
    for d, s in sorted(sim.casa.sessoes.items()):
        if s["cancelada"]:
            continue
        n = sum(1 for p in pessoas if any(x.data == d for x in p.presencas))
        por_sessao.append(n)
    print(f"cadastros: {len(pessoas)} (+{len(sim.convidados)} convidados); status: {dict(st)}")
    print(f"presenças por sessão: min {min(por_sessao)}, máx {max(por_sessao)}, média {sum(por_sessao)/len(por_sessao):.1f}")
    print("tratamentos atuais:", dict(Counter(p.tratamento for p in pessoas if p.tratamento)))
    print("entrevistas:", {"TRATAMENTO": sum(len(p.entrevistas) for p in pessoas),
                           **Counter(e["tipo"] for p in pessoas for e in p.avulsas)})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--semente", type=int, default=2118)
    ap.add_argument("--saida", default=str(Path(__file__).with_name("carga-simulacao.sql")))
    args = ap.parse_args()
    sim = Simulacao(args.semente)
    sim.simular()
    desativar_alguns(sim)
    Path(args.saida).write_text(gerar_sql(sim), encoding="utf-8")
    resumo(sim)
    print("gerado:", args.saida)


if __name__ == "__main__":
    main()
