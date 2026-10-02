// Botão "ver senha" em TODO campo de senha do sistema: login, cadastro (senha + confirmação),
// criação/edição de acesso e definição de senha pelo e-mail.
//
// É genérico de propósito. A primeira versão disso era JS inline dentro do login.html, e quando o
// cadastro ganhou dois campos de senha eles ficaram sem o botão — justamente onde ele é mais útil,
// porque ali a pessoa digita uma senha nova DUAS vezes e não tem como conferir se bate. Com o
// comportamento aqui, qualquer campo de senha novo já nasce com o botão.
//
// O botão é construído em JavaScript, e não no HTML de cada tela, para não repetir markup em cinco
// templates e para não haver dois olhos no mesmo campo (o que aconteceria se alguém esquecesse de
// remover o botão manual de uma tela).
(function () {
    "use strict";

    function criarBotao(input) {
        var botao = document.createElement("button");
        botao.type = "button";                 // sem isto, clicar envia o formulário
        botao.className = "btn btn-outline-secondary dl-ver-senha";
        botao.setAttribute("aria-pressed", "false");
        botao.setAttribute("aria-label", "Mostrar senha");
        botao.title = "Mostrar senha";
        botao.innerHTML = '<i class="bi bi-eye"></i>';

        botao.addEventListener("click", function () {
            var mostrando = input.type === "text";
            input.type = mostrando ? "password" : "text";
            botao.innerHTML = mostrando ? '<i class="bi bi-eye"></i>' : '<i class="bi bi-eye-slash"></i>';
            var rotulo = mostrando ? "Mostrar senha" : "Ocultar senha";
            botao.setAttribute("aria-label", rotulo);
            botao.setAttribute("aria-pressed", mostrando ? "false" : "true");
            botao.title = rotulo;
            input.focus();
        });
        return botao;
    }

    function ligar(input) {
        // Já está num input-group com botão (markup antigo de alguma tela)? Não mexe, para não
        // empilhar dois botões no mesmo campo.
        var grupo = input.parentElement;
        if (grupo && grupo.classList.contains("input-group") && grupo.querySelector("button")) {
            return;
        }

        if (grupo && grupo.classList.contains("input-group")) {
            grupo.appendChild(criarBotao(input));
            return;
        }

        // Caso comum: o campo está solto. Envolve num input-group para o botão encostar nele, o que
        // preserva a largura e o espaçamento que o formulário já tinha.
        var novoGrupo = document.createElement("div");
        novoGrupo.className = "input-group";
        input.parentNode.insertBefore(novoGrupo, input);
        novoGrupo.appendChild(input);
        novoGrupo.appendChild(criarBotao(input));
    }

    document.addEventListener("DOMContentLoaded", function () {
        document.querySelectorAll('input[type="password"]').forEach(ligar);
    });
})();
