package name.abuchen.portfolio.support;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Attributes;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.ClientSettings;
import name.abuchen.portfolio.model.Dashboard;
import name.abuchen.portfolio.model.InvestmentPlan;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.model.Watchlist;

/**
 * Creates an anonymized, faithful debug copy of a {@link Client} that contains
 * only a single security and the transactions related to it.
 * <p>
 * The copy is a deep copy of the given client (via a serialization round-trip);
 * the original client is never modified. In contrast to
 * {@code ClientSecurityFilter} the transactions are kept exactly as they are
 * (amounts, shares, units, taxes, fees and cross entries are preserved), so the
 * calculation for the security is identical to the original file.
 * <p>
 * The following data is stripped for anonymization:
 * <ul>
 * <li>all other securities and their transactions</li>
 * <li>all account transactions not related to the kept security (deposits,
 * removals, interest, transfers, standalone fees/taxes)</li>
 * <li>all investment plans, taxonomies, watchlists, dashboards, bookmarks,
 * attribute types, client properties and third-party extensions</li>
 * <li>notes and custom attributes of the security, accounts and portfolios</li>
 * <li>notes and source information of every transaction</li>
 * </ul>
 * Table and view column configurations ({@code ConfigurationSet}s, including
 * client filter definitions) are kept on purpose, so that column-specific
 * issues can be reproduced; the user-given names of their entries are replaced
 * with abstract, numbered labels.
 * Accounts and portfolios are renamed to generic, numbered names. Empty
 * accounts and portfolios are removed (unless an account is still the reference
 * account of a surviving portfolio). Account balances are therefore not correct
 * anymore - which is acceptable for a debug file.
 */
public final class SingleSecurityDebugClientFactory
{
    private SingleSecurityDebugClientFactory()
    {
    }

    /**
     * Creates the anonymized single-security debug copy for the given security.
     *
     * @param original
     *            the client to copy from; it is not modified
     * @param security
     *            the security to keep (identified by its UUID)
     * @return a new, independent client containing only the security and its
     *         related transactions
     */
    public static Client create(Client original, Security security) throws IOException
    {
        Client copy = ClientFactory.duplicate(original);

        Security target = copy.getSecurities().stream() //
                        .filter(s -> s.getUUID().equals(security.getUUID())) //
                        .findFirst() //
                        .orElseThrow(() -> new IllegalArgumentException(
                                        MessageFormat.format("Security {0} not found in client", security.getUUID()))); //$NON-NLS-1$

        removeOtherSecurities(copy, target);
        removeUnrelatedAccountTransactions(copy, target);
        removeInvestmentPlans(copy);
        wipeClientConfiguration(copy);
        pruneEmptyOwners(copy);

        anonymize(copy, target);
        anonymizeConfigurationSets(copy);

        return copy;
    }

    private static void removeOtherSecurities(Client client, Security target)
    {
        // removeSecurity cascades to the security's transactions (incl. cross
        // entries), investment plans, taxonomy assignments and watchlist
        // references
        for (Security security : new ArrayList<>(client.getSecurities()))
        {
            if (security != target)
                client.removeSecurity(security);
        }
    }

    private static void removeUnrelatedAccountTransactions(Client client, Security target)
    {
        // after removing all other securities, the only remaining transactions
        // that reference a security reference the target; everything without a
        // security reference (deposits, removals, interest, transfers,
        // standalone fees/taxes) is unrelated and removed. deleteTransaction
        // also removes a possible cross entry.
        for (Account account : client.getAccounts())
        {
            for (AccountTransaction t : new ArrayList<>(account.getTransactions()))
            {
                if (!target.equals(t.getSecurity()))
                    account.deleteTransaction(t, client);
            }
        }
    }

    private static void removeInvestmentPlans(Client client)
    {
        for (InvestmentPlan plan : new ArrayList<>(client.getPlans()))
            client.removePlan(plan);
    }

    private static void wipeClientConfiguration(Client client)
    {
        for (Taxonomy taxonomy : new ArrayList<>(client.getTaxonomies()))
            client.removeTaxonomy(taxonomy);

        for (Watchlist watchlist : new ArrayList<>(client.getWatchlists()))
            client.removeWatchlist(watchlist);

        for (Dashboard dashboard : client.getDashboards().collect(Collectors.toList()))
            client.removeDashboard(dashboard);

        client.clearProperties();
        client.setExtensions(new ArrayList<>());

        ClientSettings settings = client.getSettings();
        settings.clearBookmarks();
        settings.clearAttributeTypes();

        // configuration sets (table and view column configurations) are kept on
        // purpose: they define which columns are shown with which setup, which
        // is needed to reproduce column-specific issues and is not sensitive
    }

    private static void pruneEmptyOwners(Client client)
    {
        // remove empty portfolios first, so that their reference account no
        // longer keeps an otherwise empty account alive
        for (Portfolio portfolio : new ArrayList<>(client.getPortfolios()))
        {
            if (portfolio.getTransactions().isEmpty())
                client.removePortfolio(portfolio);
        }

        // keep accounts that still serve as reference account of a surviving
        // portfolio, even if they have no transactions
        Set<Account> referenceAccounts = new HashSet<>();
        for (Portfolio portfolio : client.getPortfolios())
        {
            if (portfolio.getReferenceAccount() != null)
                referenceAccounts.add(portfolio.getReferenceAccount());
        }

        for (Account account : new ArrayList<>(client.getAccounts()))
        {
            if (account.getTransactions().isEmpty() && !referenceAccounts.contains(account))
                client.removeAccount(account);
        }
    }

    private static void anonymize(Client client, Security target)
    {
        // security: keep name, ISIN, WKN, ticker, feed URLs, currency and
        // prices; strip note and all attributes
        target.setNote(null);
        target.setAttributes(new Attributes());

        // accounts and portfolios: rename generically and strip note and
        // attributes
        List<Portfolio> portfolios = new ArrayList<>(client.getPortfolios());
        for (int ii = 0; ii < portfolios.size(); ii++)
        {
            Portfolio portfolio = portfolios.get(ii);
            portfolio.setName(MessageFormat.format("Portfolio {0}", ii + 1)); //$NON-NLS-1$
            portfolio.setNote(null);
            portfolio.setAttributes(new Attributes());
        }

        List<Account> accounts = new ArrayList<>(client.getAccounts());
        for (int ii = 0; ii < accounts.size(); ii++)
        {
            Account account = accounts.get(ii);
            account.setName(MessageFormat.format("Account {0}", ii + 1)); //$NON-NLS-1$
            account.setNote(null);
            account.setAttributes(new Attributes());
        }

        // transactions: strip note and source
        for (Account account : client.getAccounts())
        {
            for (AccountTransaction t : account.getTransactions())
                scrub(t);
        }
        for (Portfolio portfolio : client.getPortfolios())
        {
            for (PortfolioTransaction t : portfolio.getTransactions())
                scrub(t);
        }
    }

    private static void scrub(Transaction t)
    {
        t.setNote(null);
        t.setSource(null);
    }

    private static void anonymizeConfigurationSets(Client client)
    {
        // configuration sets are kept (they define the table/view columns), but
        // their entries carry user-given names; replace them with abstract,
        // numbered labels. Configurations are referenced by UUID, so renaming is
        // safe.
        int[] counter = { 0 };
        for (var entry : client.getSettings().getConfigurationSets())
            entry.getValue().getConfigurations().forEach(config -> config.setName("#" + (++counter[0]))); //$NON-NLS-1$
    }
}
