package name.abuchen.portfolio.ui.handlers.tools;

import java.io.IOException;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.eclipse.e4.core.di.annotations.CanExecute;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.e4.ui.workbench.modeling.EPartService.PartState;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;

import name.abuchen.portfolio.bootstrap.BundleMessages;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.support.SingleSecurityDebugClientFactory;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.dialogs.ListSelectionDialog;
import name.abuchen.portfolio.ui.editor.ClientInput;
import name.abuchen.portfolio.ui.editor.ClientInputFactory;
import name.abuchen.portfolio.ui.handlers.MenuHelper;

public class CreateSingleSecurityDebugFileHandler
{
    @Inject
    private ClientInputFactory clientInputFactory;

    @CanExecute
    boolean isVisible(@Named(IServiceConstants.ACTIVE_PART) MPart part)
    {
        return MenuHelper.isClientPartActive(part);
    }

    @Execute
    public void execute(@Named(IServiceConstants.ACTIVE_PART) MPart part,
                    @Named(IServiceConstants.ACTIVE_SHELL) Shell shell, //
                    MApplication app, EPartService partService, EModelService modelService)
    {
        var clientInput = MenuHelper.getActiveClientInput(part);
        if (clientInput.isEmpty())
            return;

        Client client = clientInput.get().getClient();
        if (client == null)
            return;

        // let the user pick a single security

        Security security = pickSecurity(shell, client);
        if (security == null)
            return;

        // create the anonymized debug copy and open it in a new, unsaved editor
        // so that the user can inspect it and decide where (and whether) to save

        try
        {
            Client copy = SingleSecurityDebugClientFactory.create(client, security);
            openInNewWindow(copy, security, part, app, partService, modelService);
        }
        catch (IOException e)
        {
            MessageDialog.openError(shell, Messages.LabelError, e.getMessage());
        }
    }

    private Security pickSecurity(Shell shell, Client client)
    {
        ListSelectionDialog dialog = new ListSelectionDialog(shell,
                        LabelProvider.createTextProvider(element -> ((Security) element).getName()));
        dialog.setTitle(BundleMessages.getString(BundleMessages.Label.Command.createSingleSecurityDebugFile));
        dialog.setMessage(Messages.ColumnSecurity);
        dialog.setMultiSelection(false);
        dialog.setElements(client.getSecurities().stream().sorted(new Security.ByName(client.getSecurityNameConfig()))
                        .toList());

        if (dialog.open() != Window.OK || dialog.getResult().length != 1)
            return null;

        return (Security) dialog.getResult()[0];
    }

    private void openInNewWindow(Client copy, Security security, MPart activePart, MApplication app,
                    EPartService partService, EModelService modelService)
    {
        String label = "debug-" + sanitize(security.getName()) + ".xml"; //$NON-NLS-1$ //$NON-NLS-2$

        ClientInput clientInput = clientInputFactory.create(label, copy);
        // mark dirty so the user is prompted to save when closing the editor
        clientInput.markDirty();

        MPart part = partService.createPart(UIConstants.Part.PORTFOLIO);
        part.setLabel(label);
        part.getTransientData().put(ClientInput.class.getName(), clientInput);

        if (activePart != null)
            activePart.getParent().getChildren().add(part);
        else
            ((MPartStack) modelService.find(UIConstants.PartStack.MAIN, app)).getChildren().add(part);

        part.setVisible(true);
        part.getParent().setVisible(true);
        partService.showPart(part, PartState.ACTIVATE);
    }

    private String sanitize(String name)
    {
        if (name == null || name.isBlank())
            return "security"; //$NON-NLS-1$
        return name.replaceAll("[^a-zA-Z0-9-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
