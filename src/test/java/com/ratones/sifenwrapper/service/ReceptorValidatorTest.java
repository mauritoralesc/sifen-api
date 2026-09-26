package com.ratones.sifenwrapper.service;

import com.ratones.sifenwrapper.dto.request.ClienteDTO;
import com.ratones.sifenwrapper.dto.request.DataDTO;
import com.ratones.sifenwrapper.dto.request.ItemDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceptorValidatorTest {

    private final ReceptorValidator validator = new ReceptorValidator();

    @Test
    void contribuyenteB2bPersonaJuridicaEsValido() {
        assertThatCode(() -> validator.validar(factura(contribuyente("80069563-1", 1, 2))))
                .doesNotThrowAnyException();
    }

    @Test
    void contribuyenteAceptaDvCeroCuandoElRestoEsUno() {
        // Resto módulo 11 = 1 ⇒ DV 0 (algoritmo SET); es el caso que el ERP calculaba como 1.
        assertThatCode(() -> validator.validar(factura(contribuyente("80000003-0", 1, 2))))
                .doesNotThrowAnyException();
    }

    @Test
    void contribuyenteToleraCamposDeDocumentoQueElXmlIgnora() {
        ClienteDTO cliente = contribuyente("80069563-1", 1, 2);
        cliente.setITipIDRec(1);
        cliente.setDNumIDRec("80069563-1");

        assertThatCode(() -> validator.validar(factura(cliente))).doesNotThrowAnyException();
    }

    @Test
    void contribuyenteSinTipoContribuyenteSeRechaza1302() {
        assertThatThrownBy(() -> validator.validar(factura(contribuyente("80069563-1", 1, 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1302");
    }

    @Test
    void contribuyenteSinRucSeRechaza1304() {
        assertThatThrownBy(() -> validator.validar(factura(contribuyente(null, 1, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1304");
    }

    @Test
    void contribuyenteConDvIncorrectoSeRechaza1309() {
        assertThatThrownBy(() -> validator.validar(factura(contribuyente("80000003-1", 1, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1309")
                .hasMessageContaining("80000003-0");
    }

    @Test
    void contribuyenteConB2fSeRechaza1300() {
        assertThatThrownBy(() -> validator.validar(factura(contribuyente("80069563-1", 4, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1300");
    }

    @Test
    void noContribuyenteB2cConCedulaEsValido() {
        assertThatCode(() -> validator.validar(factura(noContribuyente(2, 1, "1234567"))))
                .doesNotThrowAnyException();
    }

    @Test
    void noContribuyenteToleraTipoContribuyenteQueElXmlIgnora() {
        ClienteDTO cliente = noContribuyente(2, 5, "0");
        cliente.setTipoContribuyente(2);

        assertThatCode(() -> validator.validar(factura(cliente))).doesNotThrowAnyException();
    }

    @Test
    void noContribuyenteB2bSeRechaza1300() {
        assertThatThrownBy(() -> validator.validar(factura(noContribuyente(1, 1, "1234567"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1300");
    }

    @Test
    void noContribuyenteConRucSeRechaza1305() {
        ClienteDTO cliente = noContribuyente(2, 1, "1234567");
        cliente.setRuc("80069563-1");

        assertThatThrownBy(() -> validator.validar(factura(cliente)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1305");
    }

    @Test
    void noContribuyenteSinDocumentoSeRechaza1310() {
        assertThatThrownBy(() -> validator.validar(factura(noContribuyente(2, null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1310");
    }

    @Test
    void tipoOperacionAusenteSeRechaza() {
        assertThatThrownBy(() -> validator.validar(factura(noContribuyente(0, 1, "1234567"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tipoOperacion");
    }

    @Test
    void innominadoDesde60MillonesSeRechaza1321() {
        DataDTO data = factura(noContribuyente(2, 5, "0"));
        data.setItems(List.of(item(new BigDecimal("60000000"))));

        assertThatThrownBy(() -> validator.validar(data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1321");
    }

    @Test
    void notaDeCreditoAInnominadoSeRechaza1331() {
        DataDTO data = factura(noContribuyente(2, 5, "0"));
        data.setTipoDocumento(5);

        assertThatThrownBy(() -> validator.validar(data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1331");
    }

    private static DataDTO factura(ClienteDTO cliente) {
        DataDTO data = new DataDTO();
        data.setTipoDocumento(1);
        data.setCliente(cliente);
        data.setItems(List.of(item(new BigDecimal("10000"))));
        return data;
    }

    private static ClienteDTO contribuyente(String ruc, int tipoOperacion, int tipoContribuyente) {
        ClienteDTO cliente = new ClienteDTO();
        cliente.setContribuyente(true);
        cliente.setRuc(ruc);
        cliente.setTipoOperacion(tipoOperacion);
        cliente.setTipoContribuyente(tipoContribuyente);
        cliente.setRazonSocial("EMPRESA S.A.");
        return cliente;
    }

    private static ClienteDTO noContribuyente(int tipoOperacion, Integer tipoDocumento, String numeroDocumento) {
        ClienteDTO cliente = new ClienteDTO();
        cliente.setContribuyente(false);
        cliente.setTipoOperacion(tipoOperacion);
        cliente.setITipIDRec(tipoDocumento);
        cliente.setDNumIDRec(numeroDocumento);
        cliente.setRazonSocial("Juan Pérez");
        return cliente;
    }

    private static ItemDTO item(BigDecimal precio) {
        ItemDTO item = new ItemDTO();
        item.setCantidad(BigDecimal.ONE);
        item.setPrecioUnitario(precio);
        return item;
    }
}
