package it.nextworks.corda.flows;

import com.google.common.collect.ImmutableList;
import it.nextworks.corda.contracts.PkgOfferUtils;
import it.nextworks.corda.states.PkgLicenseState;
import it.nextworks.corda.states.PkgOfferState;
import it.nextworks.corda.states.productOfferingPrice.ProductOfferingPrice;
import net.corda.core.concurrent.CordaFuture;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.ContractState;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.WireTransaction;
import net.corda.finance.Currencies;
import net.corda.finance.contracts.asset.Cash;
import net.corda.testing.node.*;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;

import static it.nextworks.corda.flows.BuyPkgFlowUtils.*;
import static net.corda.finance.workflows.GetBalances.getCashBalance;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class BuyPkgFlowTest {

    private MockNetwork mockNetwork;
    private StartedMockNode devNodeTest;
    private StartedMockNode buyerNodeTest;
    private StartedMockNode repositoryNodeTest;

    /** Build a mock network composed by a developer Node, a buyer Node and the repository Node and a Notary */
    @Before
    public void setup() {
        mockNetwork = new MockNetwork(new MockNetworkParameters(
                ImmutableList.of(
                        TestCordapp.findCordapp(cordAppContractsPkg),
                        TestCordapp.findCordapp(cordAppFlowsPkg),
                        TestCordapp.findCordapp(cordAppFinance)))
                .withNotarySpecs(ImmutableList.of(
                        new MockNetworkNotarySpec(CordaX500Name.parse(notaryX500Name)))));
        devNodeTest = mockNetwork.createPartyNode(CordaX500Name.parse(devX500Name));

        repositoryNodeTest = mockNetwork.createPartyNode(CordaX500Name.parse(repositoryX500Name));
        buyerNodeTest = mockNetwork.createPartyNode(CordaX500Name.parse(buyerX500Name));

        mockNetwork.runNetwork();
    }

    @After
    public void tearDown() {
        mockNetwork.stopNodes();
    }

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    /** Function used to generate a transaction that will output a FeeAgreementState */
    private void generateFeeAgreementState() throws Exception {
        EstablishFeeAgreementFlow.DevInitiation flow = new EstablishFeeAgreementFlow.DevInitiation(15);
        CordaFuture<SignedTransaction> future = devNodeTest.startFlow(flow);

        mockNetwork.runNetwork();

        future.get();
    }

    /**
     * Function used to generate a transaction that will output a PkgOfferState that will
     * be used in a PkgLicenseState transaction as component of a PkgLicenseState
     */
    private PkgOfferState generatePkgOfferState() throws Exception {
        ProductOfferingPrice poPrice = new ProductOfferingPrice(PkgOfferUtils.testPoId, PkgOfferUtils.testLink,
                PkgOfferUtils.testDescription, PkgOfferUtils.testIsBundle, PkgOfferUtils.testLastUpdate,
                PkgOfferUtils.testLifecycleStatus, PkgOfferUtils.testPoName, PkgOfferUtils.testPercentage,
                PkgOfferUtils.testPriceType, PkgOfferUtils.testRecChargePeriodLength, PkgOfferUtils.testRecChargePeriodType,
                PkgOfferUtils.testVersion, PkgOfferUtils.testPrice, PkgOfferUtils.testQuantity,
                PkgOfferUtils.testValidFor);
        RegisterPkgFlow.DevInitiation flow = new RegisterPkgFlow.DevInitiation(PkgOfferUtils.testName,
                PkgOfferUtils.testDescription, PkgOfferUtils.testVersion, PkgOfferUtils.testPkgInfoId,
                PkgOfferUtils.testLink, PkgOfferUtils.testPkgType, poPrice);
        CordaFuture<SignedTransaction> future = devNodeTest.startFlow(flow);

        mockNetwork.runNetwork();

        SignedTransaction signedTransaction = future.get();

        return signedTransaction.getTx().outputsOfType(PkgOfferState.class).get(0);
    }

    private void issueCash(Amount<Currency> amount) {
        SelfIssueCashFlow flow = new SelfIssueCashFlow(amount);
        buyerNodeTest.startFlow(flow);

        mockNetwork.runNetwork();
    }

    @Test
    public void signedTransactionReturnedByTheFlowIsSignedByTheInitiator() throws Exception {
        generateFeeAgreementState();
        PkgOfferState pkgOfferState = generatePkgOfferState();
        issueCash(pkgOfferState.getPrice());

        BuyPkgFlow.PkgBuyerInitiation flow =
                new BuyPkgFlow.PkgBuyerInitiation(pkgOfferState.getLinearId(), pkgOfferState.getPrice());
        CordaFuture<SignedTransaction> future = buyerNodeTest.startFlow(flow);

        mockNetwork.runNetwork();

        SignedTransaction signedTx = future.get();
        signedTx.verifySignaturesExcept(repositoryNodeTest.getInfo().getLegalIdentities().get(0).getOwningKey());
    }

    @Test
    public void signedTransactionReturnedByTheFlowIsSignedByTheAcceptor() throws Exception {
        generateFeeAgreementState();
        PkgOfferState pkgOfferState = generatePkgOfferState();
        issueCash(pkgOfferState.getPrice());

        BuyPkgFlow.PkgBuyerInitiation flow =
                new BuyPkgFlow.PkgBuyerInitiation(pkgOfferState.getLinearId(), pkgOfferState.getPrice());
        CordaFuture<SignedTransaction> future = buyerNodeTest.startFlow(flow);

        mockNetwork.runNetwork();

        SignedTransaction signedTx = future.get();
        signedTx.verifySignaturesExcept(buyerNodeTest.getInfo().getLegalIdentities().get(0).getOwningKey());
    }

    @Test
    public void flowReturnsCommittedTransaction() throws Exception {
        generateFeeAgreementState();
        PkgOfferState pkgOfferState = generatePkgOfferState();
        issueCash(pkgOfferState.getPrice());

        BuyPkgFlow.PkgBuyerInitiation flow =
                new BuyPkgFlow.PkgBuyerInitiation(pkgOfferState.getLinearId(), pkgOfferState.getPrice());
        CordaFuture<SignedTransaction> future = buyerNodeTest.startFlow(flow);

        mockNetwork.runNetwork();

        future.get().verifyRequiredSignatures();
    }

    @Test
    public void flowRecordsATransactionInBothPartiesTransactionStorages() throws Exception {
        generateFeeAgreementState();
        PkgOfferState pkgOfferState = generatePkgOfferState();
        issueCash(pkgOfferState.getPrice());

        BuyPkgFlow.PkgBuyerInitiation flow =
                new BuyPkgFlow.PkgBuyerInitiation(pkgOfferState.getLinearId(), pkgOfferState.getPrice());
        CordaFuture<SignedTransaction> future = buyerNodeTest.startFlow(flow);

        mockNetwork.runNetwork();

        SignedTransaction signedTx = future.get();
        for(StartedMockNode node : ImmutableList.of(buyerNodeTest, repositoryNodeTest)) {
            assertEquals(signedTx, node.getServices().getValidatedTransactions().getTransaction(signedTx.getId()));
        }
    }

    @Test
    public void recordedTransactionIsCorrectlyFormed() throws Exception {
        generateFeeAgreementState();
        PkgOfferState pkgOfferState = generatePkgOfferState();
        UniqueIdentifier pkgId = pkgOfferState.getLinearId();
        issueCash(pkgOfferState.getPrice());

        BuyPkgFlow.PkgBuyerInitiation flow =
                new BuyPkgFlow.PkgBuyerInitiation(pkgId, pkgOfferState.getPrice());
        CordaFuture<SignedTransaction> future = buyerNodeTest.startFlow(flow);

        mockNetwork.runNetwork();

        SignedTransaction signedTx = future.get();
        for(StartedMockNode node : ImmutableList.of(buyerNodeTest, repositoryNodeTest)) {
            SignedTransaction recordedTx = node.getServices().getValidatedTransactions()
                    .getTransaction(signedTx.getId());
            WireTransaction tx = recordedTx.getTx();

            assert (tx.getInputs().size() > 0);

            int pkgLicenseStateCount = 0;
            int cashOutputStateCount = 0;
            for(ContractState output : tx.getOutputStates()) {
                if(output instanceof PkgLicenseState)
                    pkgLicenseStateCount++;
                else if(output instanceof Cash.State)
                    cashOutputStateCount++;
                else
                    throw new IllegalArgumentException(buyPkgOutputCashErr);
            }
            assert (cashOutputStateCount > 0);
            assert (pkgLicenseStateCount == 1);

            final PkgLicenseState pkgLicenseState = tx.outputsOfType(PkgLicenseState.class).get(0);
            final PkgOfferState savedPkgOfferState = pkgLicenseState.getPkgLicensed().getState().getData();
            checkPkgOfferStateCorrectness(savedPkgOfferState, pkgId, pkgOfferState.getPoPrice());
            assertEquals(pkgLicenseState.getBuyer(), buyerNodeTest.getInfo().getLegalIdentities().get(0));
        }
    }

    @Test
    public void flowRecordsTheCorrectPkgLicenseInBothPartiesVaults() throws Exception {
        generateFeeAgreementState();
        PkgOfferState pkgOfferState = generatePkgOfferState();
        UniqueIdentifier pkgId = pkgOfferState.getLinearId();
        issueCash(pkgOfferState.getPrice());

        BuyPkgFlow.PkgBuyerInitiation flow =
                new BuyPkgFlow.PkgBuyerInitiation(pkgId, pkgOfferState.getPrice());
        CordaFuture<SignedTransaction> future = buyerNodeTest.startFlow(flow);

        mockNetwork.runNetwork();

        future.get();
        for(StartedMockNode node : ImmutableList.of(buyerNodeTest, repositoryNodeTest)) {
            node.transaction(() -> {
                List<StateAndRef<PkgLicenseState>> pkgLicenseStates = node.getServices().getVaultService()
                        .queryBy(PkgLicenseState.class).getStates();
                assertEquals(pkgLicenseStates.size(), 1);

                final PkgLicenseState pkgLicenseState = pkgLicenseStates.get(0).getState().getData();
                final PkgOfferState savedPkgOfferState = pkgLicenseState.getPkgLicensed().getState().getData();
                checkPkgOfferStateCorrectness(savedPkgOfferState, pkgId, pkgOfferState.getPoPrice());
                assertEquals(pkgLicenseState.getBuyer(), buyerNodeTest.getInfo().getLegalIdentities().get(0));

                return null;
            });
        }
    }

    private void checkPkgOfferStateCorrectness(@NotNull PkgOfferState recordedState, @NotNull UniqueIdentifier pkgId,
                                               @NotNull ProductOfferingPrice poPrice) {
        assertEquals(recordedState.getLinearId(), pkgId);
        assertEquals(recordedState.getName(), PkgOfferUtils.testName);
        assertEquals(recordedState.getDescription(), PkgOfferUtils.testDescription);
        assertEquals(recordedState.getVersion(), PkgOfferUtils.testVersion);
        assertEquals(recordedState.getPkgInfoId(), PkgOfferUtils.testPkgInfoId);
        assertEquals(recordedState.getImageLink(), PkgOfferUtils.testLink);
        assertEquals(recordedState.getPoPrice(), poPrice);
        assertEquals(recordedState.getPkgType(), PkgOfferUtils.testPkgType);
        assertEquals(recordedState.getAuthor(), devNodeTest.getInfo().getLegalIdentities().get(0));
        assertEquals(recordedState.getRepositoryNode(), repositoryNodeTest.getInfo().getLegalIdentities().get(0));
    }

    @Test
    public void buyerMustHaveCashInRightCurrency() throws Exception {
        generateFeeAgreementState();
        PkgOfferState pkgOfferState = generatePkgOfferState();
        issueCash(Currencies.DOLLARS(1));

        BuyPkgFlow.PkgBuyerInitiation flow =
                new BuyPkgFlow.PkgBuyerInitiation(pkgOfferState.getLinearId(), pkgOfferState.getPrice());
        CordaFuture<SignedTransaction> future = buyerNodeTest.startFlow(flow);

        try {
            mockNetwork.runNetwork();
            future.get();
        } catch(Exception exception) {
            assert exception.getMessage().equals("java.lang.IllegalArgumentException: " + missingCash);
        }
    }

    @Test
    public void buyerMustHaveEnoughCashInRightCurrency() throws Exception {
        generateFeeAgreementState();
        PkgOfferState pkgOfferState = generatePkgOfferState();

        BuyPkgFlow.PkgBuyerInitiation flow =
                new BuyPkgFlow.PkgBuyerInitiation(pkgOfferState.getLinearId(), pkgOfferState.getPrice());
        CordaFuture<SignedTransaction> future = buyerNodeTest.startFlow(flow);

        try {
            mockNetwork.runNetwork();
            future.get();
        } catch(Exception exception) {
            assert exception.getMessage().equals("java.lang.IllegalArgumentException: " + missingCash);
        }
    }

    @Test
    public void pkgMustExist() throws Exception {
        generateFeeAgreementState();
        PkgOfferState pkgOfferState = generatePkgOfferState();

        UniqueIdentifier pkgId = new UniqueIdentifier();
        BuyPkgFlow.PkgBuyerInitiation flow =
                new BuyPkgFlow.PkgBuyerInitiation(pkgId, pkgOfferState.getPrice());
        CordaFuture<SignedTransaction> future = buyerNodeTest.startFlow(flow);

        try {
            mockNetwork.runNetwork();
            future.get();
        } catch(Exception exception) {
            assert exception.getMessage()
                    .equals("it.nextworks.corda.flows.BuyPkgFlow$NonExistentPkgException: " + nonExistentPkg + pkgId);
        }
    }

    @Test
    public void authorMustReceiveTheRightAmountOfCash() throws Exception {
        generateFeeAgreementState();
        PkgOfferState pkgOfferState = generatePkgOfferState();
        Amount<Currency> price = pkgOfferState.getPrice();
        issueCash(price);

        Amount<Currency> cashBalance = getCashBalance(devNodeTest.getServices(), price.getToken());
        System.out.println(cashBalance.toString());

        BuyPkgFlow.PkgBuyerInitiation flow =
                new BuyPkgFlow.PkgBuyerInitiation(pkgOfferState.getLinearId(), price);
        CordaFuture<SignedTransaction> future = buyerNodeTest.startFlow(flow);

        mockNetwork.runNetwork();

        future.get();

        cashBalance = getCashBalance(devNodeTest.getServices(), price.getToken());
        Amount<Currency> toBeingIssued =
                Amount.fromDecimal(price.toDecimal().divide(BigDecimal.valueOf(100), 4,
                        RoundingMode.UNNECESSARY).multiply(BigDecimal.valueOf(90)).setScale(2,
                        BigDecimal.ROUND_HALF_EVEN), price.getToken());
        assertEquals(cashBalance, toBeingIssued);
        System.out.println(cashBalance.toString());
    }
}
