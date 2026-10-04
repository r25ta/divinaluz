// Menu recolhível do celular (2026-10-04). Em tela larga o CSS mostra os itens em linha e este
// botão nem aparece; em tela estreita ele abre e fecha a lista. Fecha também ao tocar fora do menu
// e com Esc, que é o que se espera de um menu no celular.
(function () {
    function iniciar() {
        var navbar = document.querySelector('.dl-navbar');
        var botao = navbar && navbar.querySelector('.dl-nav-toggle');
        if (!botao) return;

        function alternar(abrir) {
            navbar.classList.toggle('aberto', abrir);
            botao.setAttribute('aria-expanded', abrir ? 'true' : 'false');
            botao.setAttribute('aria-label', abrir ? 'Fechar o menu' : 'Abrir o menu');
            botao.querySelector('.bi').className = 'bi ' + (abrir ? 'bi-x-lg' : 'bi-list');
        }

        botao.addEventListener('click', function () {
            alternar(!navbar.classList.contains('aberto'));
        });
        document.addEventListener('click', function (evento) {
            if (navbar.classList.contains('aberto') && !navbar.contains(evento.target)) alternar(false);
        });
        document.addEventListener('keydown', function (evento) {
            if (evento.key === 'Escape' && navbar.classList.contains('aberto')) {
                alternar(false);
                botao.focus();
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', iniciar);
    } else {
        iniciar();
    }
})();
