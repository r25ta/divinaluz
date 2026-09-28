// Máscara dd/mm/aaaa para os campos de data do sistema (ver CLAUDE.md: todas as datas são
// apresentadas e gravadas no formato DD/MM/YYYY). Aplica-se a qualquer <input class="dl-data">,
// digitando ou colando; o valor enviado ao backend já sai nesse formato, que o Spring
// (spring.mvc.format.date=dd/MM/yyyy + @DateTimeFormat(pattern="dd/MM/yyyy") nas entidades) faz
// o parse diretamente, sem passar por ISO.
(function () {
    function mascarar(valor) {
        const digitos = valor.replace(/\D/g, "").slice(0, 8);
        const dia = digitos.slice(0, 2);
        const mes = digitos.slice(2, 4);
        const ano = digitos.slice(4, 8);
        let resultado = dia;
        if (mes) resultado += "/" + mes;
        if (ano) resultado += "/" + ano;
        return resultado;
    }

    function aplicar(input) {
        input.addEventListener("input", function () {
            input.value = mascarar(input.value);
        });
    }

    document.addEventListener("DOMContentLoaded", function () {
        document.querySelectorAll("input.dl-data").forEach(aplicar);
    });
})();
