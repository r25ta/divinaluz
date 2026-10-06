// Um envio por formulário (2026-10-05). No celular o toque duplo é comum, e um segundo envio grava
// de novo: cadastro rápido duplicado (que ainda volta como "homônimo" e confunde), presença ou
// avaliação repetidas. Depois do primeiro envio o formulário recusa os seguintes e os botões ficam
// desativados, com a marca de "enviando".
//
// A trava é aplicada um instante DEPOIS do evento (setTimeout 0) para respeitar quem cancelou o
// envio antes — o confirm() de um onsubmit, ou o ouvinte de data-confirmar do painel da sessão —,
// e porque os dados do formulário já foram montados nesse ponto (desativar o botão antes tiraria
// o name/value dele do envio). Formulário que abre em outra aba (target) não é travado.
(function () {
    var SELETOR_BOTOES = 'button[type="submit"], button:not([type]), input[type="submit"]';

    function liberar(form) {
        delete form.dataset.enviando;
        form.querySelectorAll(SELETOR_BOTOES).forEach(function (botao) {
            botao.disabled = false;
            botao.classList.remove('dl-enviando');
        });
    }

    document.addEventListener('submit', function (evento) {
        var form = evento.target;
        if (!(form instanceof HTMLFormElement)) return;
        if (form.target && form.target !== '_self') return;
        if (form.dataset.enviando === '1') {
            evento.preventDefault();
            return;
        }
        setTimeout(function () {
            if (evento.defaultPrevented) return;
            form.dataset.enviando = '1';
            form.querySelectorAll(SELETOR_BOTOES).forEach(function (botao) {
                botao.disabled = true;
                botao.classList.add('dl-enviando');
            });
            // Rede de segurança: se a página não mudar (rede caiu), o botão volta a funcionar.
            setTimeout(function () { liberar(form); }, 15000);
        }, 0);
    });

    // Voltar pelo navegador restaura a página da memória com os botões ainda travados.
    window.addEventListener('pageshow', function (evento) {
        if (evento.persisted) {
            document.querySelectorAll('form[data-enviando]').forEach(liberar);
        }
    });
})();
