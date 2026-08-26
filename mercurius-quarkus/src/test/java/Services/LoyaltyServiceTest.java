package Services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import Models.AppSettings;
import Models.Clients;
import Models.PuntosTransaccion;
import Models.Users;
import jakarta.persistence.EntityManager;

/**
 * Plain-Mockito unit tests for {@link LoyaltyService} pure logic (T6).
 * <p>
 * No Quarkus boot: the EntityManager, ClientService and AppSettingsService
 * collaborators are mocked; Clients/AppSettings/PuntosTransaccion are real
 * Lombok POJOs. Covers:
 * <ul>
 *   <li>calculatePointsEarned boundary table (0%, 100%, 13.5%, HALF_UP rounding at 4dp)</li>
 *   <li>redeemPoints success + insufficient-balance guard returning ZERO</li>
 *   <li>earnPoints early-return on null settings / null cashback percentage</li>
 *   <li>getAvailablePoints null-safety for legacy clients</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class LoyaltyServiceTest {

    @Mock
    private EntityManager em;

    @Mock
    private ClientService clientService;

    @Mock
    private AppSettingsService appSettingsService;

    @InjectMocks
    private LoyaltyService service;

    // --- calculatePointsEarned boundary table ---

    @Test
    void calculatePointsEarned_zeroPercentCashbackYieldsZeroPoints() {
        BigDecimal result = service.calculatePointsEarned(new BigDecimal("1000"), new BigDecimal("0"));
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void calculatePointsEarned_hundredPercentCashbackYieldsFullAmount() {
        BigDecimal result = service.calculatePointsEarned(new BigDecimal("1000"), new BigDecimal("100"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("1000"));
    }

    @Test
    void calculatePointsEarned_typicalRate135Percent() {
        BigDecimal result = service.calculatePointsEarned(new BigDecimal("10000"), new BigDecimal("13.5"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("1350"));
    }

    @Test
    void calculatePointsEarned_roundingHalfUpAt4dpRoundsUp() {
        // 0.005555% / 100 = 0.00005555 -> 4dp HALF_UP -> 0.0001 (discarded fraction >= half ulp)
        // Without the 4dp rounding the result would be 0.5555 instead of 1.
        BigDecimal result = service.calculatePointsEarned(new BigDecimal("10000"), new BigDecimal("0.005555"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("1"));
    }

    @Test
    void calculatePointsEarned_roundingHalfUpAt4dpRoundsDownBelowHalfUlp() {
        // 0.11111% / 100 = 0.0011111 -> 4dp HALF_UP -> 0.0011 (discarded fraction < half ulp)
        BigDecimal result = service.calculatePointsEarned(new BigDecimal("1000"), new BigDecimal("0.11111"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("1.1"));
    }

    @Test
    void calculatePointsEarned_fractionalAmountTimesFractionalRate() {
        BigDecimal result = service.calculatePointsEarned(new BigDecimal("99.99"), new BigDecimal("13.5"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("13.49865"));
    }

    // --- redeemPoints ---

    @Test
    void redeemPoints_successDeductsBalanceAndReturnsRedeemedAmount() {
        Clients client = new Clients();
        client.setPuntosAcumulados(new BigDecimal("100"));

        BigDecimal redeemed = service.redeemPoints(client, new BigDecimal("40"));

        assertThat(redeemed).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(client.getPuntosAcumulados()).isEqualByComparingTo(new BigDecimal("60"));

        ArgumentCaptor<PuntosTransaccion> captor = ArgumentCaptor.forClass(PuntosTransaccion.class);
        verify(em).persist(captor.capture());
        verify(em).merge(client);
        PuntosTransaccion tx = captor.getValue();
        assertThat(tx.getTipoTransaccion()).isEqualTo("redeem");
        assertThat(tx.getPuntos()).isEqualByComparingTo(new BigDecimal("-40"));
        assertThat(tx.getSaldoPuntos()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(tx.getCliente()).isSameAs(client);
    }

    @Test
    void redeemPoints_insufficientBalanceGuardReturnsZeroWithoutSideEffects() {
        Clients client = new Clients();
        client.setPuntosAcumulados(new BigDecimal("10"));

        BigDecimal redeemed = service.redeemPoints(client, new BigDecimal("50"));

        assertThat(redeemed).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(client.getPuntosAcumulados()).isEqualByComparingTo(new BigDecimal("10"));
        verifyNoInteractions(em);
    }

    @Test
    void redeemPoints_exactBalanceBoundaryRedeemsEverything() {
        Clients client = new Clients();
        client.setPuntosAcumulados(new BigDecimal("25.50"));

        BigDecimal redeemed = service.redeemPoints(client, new BigDecimal("25.50"));

        assertThat(redeemed).isEqualByComparingTo(new BigDecimal("25.50"));
        assertThat(client.getPuntosAcumulados()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // --- earnPoints ---

    @Test
    void earnPoints_nullSettingsReturnsEarlyWithoutPersistence() {
        when(appSettingsService.returnCurrent()).thenReturn(null);
        Clients client = new Clients();

        service.earnPoints(client, new BigDecimal("1000"), "F-001", new Users());

        assertThat(client.getPuntosAcumulados()).isNull();
        assertThat(client.getStatusPuntos()).isNull();
        verifyNoInteractions(em);
    }

    @Test
    void earnPoints_nullCashbackPercentageReturnsEarlyWithoutPersistence() {
        AppSettings settings = new AppSettings();
        settings.setCashbackPercentage(null);
        when(appSettingsService.returnCurrent()).thenReturn(settings);
        Clients client = new Clients();

        service.earnPoints(client, new BigDecimal("1000"), null, new Users());

        assertThat(client.getPuntosAcumulados()).isNull();
        assertThat(client.getStatusPuntos()).isNull();
        verifyNoInteractions(em);
    }

    @Test
    void earnPoints_successAccumulatesPointsAndPersistsTransaction() {
        AppSettings settings = new AppSettings();
        settings.setCashbackPercentage(new BigDecimal("13.5"));
        when(appSettingsService.returnCurrent()).thenReturn(settings);

        Clients client = new Clients();
        client.setPuntosAcumulados(new BigDecimal("100"));

        service.earnPoints(client, new BigDecimal("1000"), "F-123", new Users());

        assertThat(client.getPuntosAcumulados()).isEqualByComparingTo(new BigDecimal("235"));
        assertThat(client.getStatusPuntos()).isEqualTo("active");
        assertThat(client.getLastPurchaseDate()).isNotNull();

        ArgumentCaptor<PuntosTransaccion> captor = ArgumentCaptor.forClass(PuntosTransaccion.class);
        verify(em).persist(captor.capture());
        verify(em).merge(client);
        PuntosTransaccion tx = captor.getValue();
        assertThat(tx.getTipoTransaccion()).isEqualTo("earn");
        assertThat(tx.getPuntos()).isEqualByComparingTo(new BigDecimal("135"));
        assertThat(tx.getSaldoPuntos()).isEqualByComparingTo(new BigDecimal("235"));
        assertThat(tx.getFacturaId()).isEqualTo("F-123");
        assertThat(tx.getDescripcion()).isEqualTo("Puntos ganados por compra");
    }

    @Test
    void earnPoints_legacyClientWithNullBalanceStartsFromZero() {
        AppSettings settings = new AppSettings();
        settings.setCashbackPercentage(new BigDecimal("100"));
        when(appSettingsService.returnCurrent()).thenReturn(settings);

        Clients client = new Clients(); // puntosAcumulados left NULL (legacy row)

        service.earnPoints(client, new BigDecimal("250"), null, new Users());

        assertThat(client.getPuntosAcumulados()).isEqualByComparingTo(new BigDecimal("250"));
        ArgumentCaptor<PuntosTransaccion> captor = ArgumentCaptor.forClass(PuntosTransaccion.class);
        verify(em).persist(captor.capture());
        assertThat(captor.getValue().getSaldoPuntos()).isEqualByComparingTo(new BigDecimal("250"));
    }

    // --- getAvailablePoints ---

    @Test
    void getAvailablePoints_nullBalanceIsSafeAndReturnsZero() {
        Clients client = new Clients(); // legacy clients may have NULL puntosAcumulados

        assertThat(service.getAvailablePoints(client)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getAvailablePoints_positiveBalanceReturnedAsIs() {
        Clients client = new Clients();
        client.setPuntosAcumulados(new BigDecimal("75.25"));

        assertThat(service.getAvailablePoints(client)).isEqualByComparingTo(new BigDecimal("75.25"));
    }
}
