package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;

@ToString
@Getter
@Setter
@Entity
public class Assistido {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nome;
    private String residencia;
    private String cep;
    private String endereco;
    private String numero;
    private String complemento;
    private String bairro;
    private String cidade;
    private String uf;

    @Enumerated(EnumType.STRING)
    private CartaoStatus statusCartao = CartaoStatus.EM_TRATAMENTO;

    @DateTimeFormat(pattern = "dd/MM/yyyy")
    private LocalDate dataNascimento;

    private String estadoCivil;
    private String sexo;
    private String email;
    private String vinculo; // ASSISTIDO, TRABALHADOR, ALUNO

    // Dia da semana em que o assistido comparece à casa espírita (terça 19h ou domingo 08h).
    // Só é alterado via rota dedicada (ver HistoricoDiaFrequencia), nunca pelo formulário de
    // cadastro/edição geral, para garantir que toda mudança fique registrada no histórico.
    @Enumerated(EnumType.STRING)
    private DiaFrequencia diaFrequencia;

    @ManyToOne
    @JoinColumn(name = "tratamento_atual_id")
    @ToString.Exclude
    private TipoTratamento tratamentoAtual;

    // Data de início do ciclo de tratamento atual. Reinicia sempre que o assistido fica
    // 3 semanas (21 dias) sem comparecer, o que também zera a contagem de sessões/avaliações
    // usada para disparar a regra das 4 sessões (ver TratamentoService).
    @DateTimeFormat(pattern = "dd/MM/yyyy")
    private LocalDate cicloIniciadoEm;

    // Exclusão lógica: o assistido pode querer retomar tratamento futuramente, então o
    // prontuário nunca é removido de fato, apenas desativado (some da listagem principal).
    private boolean ativo = true;

    // Código opaco do cartão, usado no QR que a recepção escaneia no check-in. É gerado na primeira
    // vez que o cartão é aberto; o id não vai na URL do QR para não permitir adivinhar prontuários.
    @Column(name = "codigo_cartao", unique = true, length = 36)
    private String codigoCartao;

    // Dados de acesso ao sistema (opcionais): o assistido é a mesma pessoa do usuário, então o
    // login vive aqui em vez de numa entidade separada. login == null significa "sem acesso".
    @Column(unique = true, length = 80)
    private String login;

    @ToString.Exclude
    private String senha;

    @Enumerated(EnumType.STRING)
    @Column(name = "perfil_acesso", length = 20)
    private PerfilAcesso perfilAcesso;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProvedorIdentidade provedor = ProvedorIdentidade.LOCAL;

    @Column(name = "identificador_externo")
    private String identificadorExterno;

    @Column(name = "acesso_ativo", nullable = false)
    private boolean acessoAtivo = true;

    // Token de definição de senha enviado por e-mail no cadastro (ver AcessoService). Some quando
    // a senha é definida (ou o link expira/é reemitido); nunca é bindado do form de cadastro.
    @ToString.Exclude
    @Column(name = "token_definicao_senha", unique = true, length = 64)
    private String tokenDefinicaoSenha;

    @Column(name = "token_definicao_senha_expira_em")
    private LocalDateTime tokenDefinicaoSenhaExpiraEm;

    @Transient
    public Integer getIdade() {
        return dataNascimento != null ? Period.between(dataNascimento, LocalDate.now()).getYears() : null;
    }
}