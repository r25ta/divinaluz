package br.com.nae.divinaluz.config;

import br.com.nae.divinaluz.model.TipoTratamento;
import br.com.nae.divinaluz.repository.TipoTratamentoRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TipoTratamentoConverter implements Converter<String, TipoTratamento> {

    private final TipoTratamentoRepository tipoTratamentoRepository;

    public TipoTratamentoConverter(TipoTratamentoRepository tipoTratamentoRepository) {
        this.tipoTratamentoRepository = tipoTratamentoRepository;
    }

    @Override
    public TipoTratamento convert(String source) {
        if (!StringUtils.hasText(source)) {
            return null;
        }
        return tipoTratamentoRepository.findById(Long.valueOf(source)).orElse(null);
    }
}
