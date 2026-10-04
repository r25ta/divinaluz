// Tabelas no celular (2026-10-04): em tela estreita, cada linha de uma .dl-table vira um cartão, com
// o título da coluna ao lado de cada valor. Este script só copia o texto de cada <th> para o
// data-label das células da coluna; quem empilha é o CSS (.dl-table-rotulada, em app.css). Assim
// nenhuma tela precisa repetir os rótulos no HTML, e uma tabela nova já nasce responsiva.
//
// Fica de fora quem tiver a classe .dl-table-fixa (tabela pequena que cabe como está).
// Observa o DOM porque há tabelas que são trocadas sem recarregar a página (a lista de presentes do
// painel da sessão é atualizada a cada 10s).
(function () {
    function rotular(tabela) {
        var titulos = Array.prototype.map.call(tabela.querySelectorAll('thead th'), function (th) {
            return th.textContent.replace(/\s+/g, ' ').trim();
        });
        if (titulos.length === 0) return;
        tabela.querySelectorAll('tbody tr').forEach(function (linha) {
            var coluna = 0;
            Array.prototype.forEach.call(linha.children, function (celula) {
                if (!celula.hasAttribute('data-label')) {
                    celula.setAttribute('data-label', titulos[coluna] || '');
                }
                coluna += celula.colSpan || 1;
            });
        });
        tabela.classList.add('dl-table-rotulada');
    }

    function rotularDentroDe(raiz) {
        if (raiz.matches && raiz.matches('table.dl-table:not(.dl-table-fixa)')) {
            rotular(raiz);
            return;
        }
        if (raiz.querySelectorAll) {
            raiz.querySelectorAll('table.dl-table:not(.dl-table-fixa)').forEach(rotular);
        }
    }

    function iniciar() {
        rotularDentroDe(document);
        new MutationObserver(function (mudancas) {
            mudancas.forEach(function (mudanca) {
                mudanca.addedNodes.forEach(function (no) {
                    if (no.nodeType === 1) rotularDentroDe(no);
                });
            });
        }).observe(document.body, {childList: true, subtree: true});
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', iniciar);
    } else {
        iniciar();
    }
})();
