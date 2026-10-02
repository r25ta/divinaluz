// Calendário para os campos de data do sistema. Trabalha junto com data-mask.js: o campo continua
// sendo <input type="text" class="dl-data"> e a digitação manual continua valendo — o calendário é
// uma segunda forma de preencher, não a única.
//
// POR QUE NÃO <input type="date"> nem biblioteca de terceiros:
//   - type="date" mostra a data no formato do locale do SISTEMA OPERACIONAL, então o mesmo campo
//     apareceria como mm/dd/yyyy em algumas máquinas (ver seção 6 do CLAUDE.md, que proíbe isso);
//   - uma biblioteca por CDN seria o primeiro JavaScript de terceiros em telas que mostram
//     prontuário de pessoas reais. O projeto já carrega CSS do Bootstrap de CDN, mas nenhum JS, e
//     script de terceiros nessas páginas lê o que está nelas.
//
// RESTRIÇÃO DE DIAS — o atributo data-dias:
//   ausente            qualquer dia (nascimento, avaliação: a data é livre)
//   data-dias="0,2"    só domingo e terça (preleção, abrir sessão, 1ª sessão de quem ainda não tem dia)
//   data-dias="2"      só terça (quem tem diaFrequencia = TERCA_19H)
// Os números são os do JavaScript (0 = domingo … 6 = sábado); no backend vêm de
// DiaFrequencia.getIndiceJs(). Sem data-dias nenhum dia é bloqueado — é por isso que a data de
// nascimento e a da avaliação não quebram com este componente.
(function () {
    "use strict";

    var MESES = ["Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho",
                 "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro"];
    var DIAS = ["D", "S", "T", "Q", "Q", "S", "S"];

    var aberto = null;   // { input, painel, mes, ano }

    function diasPermitidos(input) {
        var attr = input.getAttribute("data-dias");
        if (!attr) { return null; }                       // null = sem restrição
        return attr.split(",")
            .map(function (n) { return parseInt(n.trim(), 10); })
            .filter(function (n) { return !isNaN(n) && n >= 0 && n <= 6; });
    }

    function permitido(data, dias) {
        return !dias || dias.length === 0 || dias.indexOf(data.getDay()) !== -1;
    }

    // dd/mm/aaaa -> Date, ou null. Confere que a data existe de verdade: "31/02/2026" vira null em
    // vez de 03/03, que é o que o construtor de Date faria sozinho.
    function lerData(texto) {
        var m = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec((texto || "").trim());
        if (!m) { return null; }
        var dia = parseInt(m[1], 10), mes = parseInt(m[2], 10) - 1, ano = parseInt(m[3], 10);
        var d = new Date(ano, mes, dia);
        if (d.getDate() !== dia || d.getMonth() !== mes || d.getFullYear() !== ano) { return null; }
        return d;
    }

    function escreverData(d) {
        var dd = String(d.getDate()).padStart(2, "0");
        var mm = String(d.getMonth() + 1).padStart(2, "0");
        return dd + "/" + mm + "/" + d.getFullYear();
    }

    function mesmoDia(a, b) {
        return a.getDate() === b.getDate() && a.getMonth() === b.getMonth()
            && a.getFullYear() === b.getFullYear();
    }

    function fechar() {
        if (!aberto) { return; }
        aberto.painel.remove();
        aberto = null;
    }

    function posicionar(input, painel) {
        var r = input.getBoundingClientRect();
        painel.style.top = (window.scrollY + r.bottom + 4) + "px";
        painel.style.left = (window.scrollX + r.left) + "px";
    }

    function desenhar() {
        if (!aberto) { return; }
        var input = aberto.input;
        var painel = aberto.painel;
        var dias = diasPermitidos(input);
        var selecionada = lerData(input.value);
        var hoje = new Date();

        var primeiro = new Date(aberto.ano, aberto.mes, 1);
        var totalDias = new Date(aberto.ano, aberto.mes + 1, 0).getDate();

        var html = '<div class="dl-dp-cabecalho">'
            + '<button type="button" class="dl-dp-nav" data-mover="-1" aria-label="Mês anterior"><i class="bi bi-chevron-left"></i></button>'
            + '<span class="dl-dp-titulo">' + MESES[aberto.mes] + " " + aberto.ano + "</span>"
            + '<button type="button" class="dl-dp-nav" data-mover="1" aria-label="Próximo mês"><i class="bi bi-chevron-right"></i></button>'
            + "</div><div class=\"dl-dp-grade\">";

        DIAS.forEach(function (d) { html += '<span class="dl-dp-semana">' + d + "</span>"; });
        for (var vazio = 0; vazio < primeiro.getDay(); vazio++) { html += "<span></span>"; }

        for (var dia = 1; dia <= totalDias; dia++) {
            var data = new Date(aberto.ano, aberto.mes, dia);
            var classes = ["dl-dp-dia"];
            if (!permitido(data, dias)) { classes.push("dl-dp-bloqueado"); }
            if (mesmoDia(data, hoje)) { classes.push("dl-dp-hoje"); }
            if (selecionada && mesmoDia(data, selecionada)) { classes.push("dl-dp-selecionado"); }
            html += '<button type="button" class="' + classes.join(" ") + '" data-dia="' + dia + '"'
                + (permitido(data, dias) ? "" : " disabled") + ">" + dia + "</button>";
        }
        html += "</div>";

        // Quando só alguns dias valem, dizer qual é — senão o calendário parece quebrado.
        if (dias && dias.length > 0) {
            var nomes = { 0: "domingos", 1: "segundas", 2: "terças", 3: "quartas",
                          4: "quintas", 5: "sextas", 6: "sábados" };
            html += '<div class="dl-dp-nota">Somente '
                + dias.map(function (d) { return nomes[d]; }).join(" e ") + "</div>";
        }
        painel.innerHTML = html;
    }

    function abrir(input) {
        if (aberto && aberto.input === input) { return; }
        fechar();

        var base = lerData(input.value) || new Date();
        var painel = document.createElement("div");
        painel.className = "dl-dp";
        painel.setAttribute("role", "dialog");
        painel.setAttribute("aria-label", "Selecionar data");
        document.body.appendChild(painel);

        aberto = { input: input, painel: painel, mes: base.getMonth(), ano: base.getFullYear() };
        desenhar();
        posicionar(input, painel);

        painel.addEventListener("mousedown", function (e) { e.preventDefault(); });  // não tira o foco do campo
        painel.addEventListener("click", function (e) {
            var nav = e.target.closest("[data-mover]");
            if (nav) {
                aberto.mes += parseInt(nav.getAttribute("data-mover"), 10);
                if (aberto.mes < 0) { aberto.mes = 11; aberto.ano--; }
                if (aberto.mes > 11) { aberto.mes = 0; aberto.ano++; }
                desenhar();
                return;
            }
            var botao = e.target.closest("[data-dia]");
            if (botao && !botao.disabled) {
                var escolhida = new Date(aberto.ano, aberto.mes, parseInt(botao.getAttribute("data-dia"), 10));
                input.value = escreverData(escolhida);
                input.dispatchEvent(new Event("input", { bubbles: true }));
                input.dispatchEvent(new Event("change", { bubbles: true }));
                conferir(input);
                // Fechar e NÃO chamar input.focus(): o campo nunca perdeu o foco (o mousedown do
                // painel é cancelado justamente para isso), e um focus() aqui disparia o listener de
                // foco e reabriria o calendário na hora, deixando-o impossível de fechar escolhendo
                // uma data.
                fechar();
            }
        });
    }

    // Avisa, sem bloquear, quando a data digitada cai num dia que aquele campo não aceita. O backend
    // (TratamentoService/PrelecaoController) continua sendo quem decide — isto é só para a pessoa
    // perceber antes de enviar o formulário.
    function conferir(input) {
        var texto = (input.value || "").trim();
        var data = lerData(texto);
        var dias = diasPermitidos(input);

        // Data completa mas impossível (31/02/2026): o pattern do input aceita, porque casa com
        // \d{2}/\d{2}/\d{4}, e só o backend recusaria — no parse, o que chega como erro genérico.
        var impossivel = texto.length === 10 && !data;
        var diaErrado = data && dias && dias.length > 0 && !permitido(data, dias);

        input.classList.toggle("is-invalid", impossivel || !!diaErrado);
    }

    function ligar(input) {
        input.setAttribute("autocomplete", "off");
        input.addEventListener("focus", function () { abrir(input); });
        input.addEventListener("click", function () { abrir(input); });
        input.addEventListener("input", function () {
            conferir(input);
            var d = lerData(input.value);
            if (d && aberto && aberto.input === input) {
                aberto.mes = d.getMonth();
                aberto.ano = d.getFullYear();
                desenhar();
            }
        });
        input.addEventListener("blur", function () { conferir(input); });
        input.addEventListener("keydown", function (e) {
            if (e.key === "Escape") { fechar(); }
        });
        conferir(input);
    }

    document.addEventListener("DOMContentLoaded", function () {
        document.querySelectorAll("input.dl-data").forEach(ligar);
    });

    document.addEventListener("mousedown", function (e) {
        if (aberto && !aberto.painel.contains(e.target) && e.target !== aberto.input) { fechar(); }
    });
    window.addEventListener("resize", function () { if (aberto) { posicionar(aberto.input, aberto.painel); } });
    window.addEventListener("scroll", function () { if (aberto) { posicionar(aberto.input, aberto.painel); } }, true);
})();
