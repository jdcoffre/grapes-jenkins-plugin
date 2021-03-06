package org.axway.grapes.jenkins;


import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.maven.AbstractMavenProject;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.axway.grapes.commons.datamodel.Module;
import org.axway.grapes.jenkins.config.GrapesConfig;
import org.axway.grapes.jenkins.notifications.GrapesNotification;
import org.axway.grapes.jenkins.notifications.GrapesNotification.NotificationType;
import org.axway.grapes.jenkins.reports.GrapesBuildAction;
import org.axway.grapes.jenkins.resend.ResendBuildAction;
import org.axway.grapes.jenkins.resend.ResendProjectAction;
import org.axway.grapes.utils.client.GrapesClient;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * Grapes Notifier
 *
 * <p>Handle the notification to Grapes server. It gets the report from the build, it perform the notification to the Grapes server.
 * It handles also the resend actions creation in case notification failure.</p>
 *
 * @author jdcoffre
 */
public class GrapesNotifier extends Notifier {

    // Name of current Grapes configuration
    private String configName;

    // Manage the reports of Grapes Maven plugin
    public Boolean manageGrapesMavenPlugin = false;

    public Boolean getManageGrapesMavenPlugin() {
        return manageGrapesMavenPlugin;
    }

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public GrapesNotifier(final String configName, final boolean manageGrapesMavenPlugin) {
        this.configName = configName;
        this.manageGrapesMavenPlugin = manageGrapesMavenPlugin;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.STEP;
    }

    /**
     * Performs grapes notification, at the end of the build.
     *
     * @param build    AbstractBuild<?, ?>
     * @param launcher Launcher
     * @param listener BuildListener
     * @return boolean
     */
    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) {
        // No Publication for failed builds
        if (build.getResult() == null ||
                build.getResult().isWorseThan(Result.SUCCESS)) {
            listener.getLogger().println("[GRAPES] Skipping notification to Grapes because the result of this build is worth than success.");
            return true;
        }

        final List<GrapesNotification> notifications = GrapesPlugin.getAllNotifications(build);
        if(notifications.isEmpty()){
            listener.getLogger().println("[GRAPES] No Grapes notification to send.");
            return true;
        }

        final PrintStream logger = listener.getLogger();
        final AbstractProject<?, ?> project = build.getParent();

        final GrapesConfig config = getConfig();


        logger.println("[GRAPES] Connection to Grapes");
        logger.println("[GRAPES] Host: " + config.getHost());
        logger.println("[GRAPES] Port: " + config.getPort());

        // Manage server configuration
        final GrapesClient client = new GrapesClient(config.getHost(), String.valueOf(config.getPort()));
        String user = null, password = null;

        if (config.getPublisherCredentials() != null) {
            user = config.getPublisherCredentials().getUsername();
            password = config.getPublisherCredentials().getPassword();
        }

        // Send notifications
        for(GrapesNotification notification: notifications){
            try {
                switch (notification.getNotificationAction()){
                    case POST_MODULE: sendModule(build, notification, client, user, password, logger);
                        break;
                    case PROMOTE: promoteModule(build, notification, client, user, password, logger);
                        break;
                    default:break;
                }

            } catch (Exception e) {
                logger.println("[GRAPES] The notification has been postpone due to an error.");
                GrapesPlugin.getLogger().log(Level.SEVERE, "[GRAPES] An error occurred! ", e);
            }
        }

        return true;
    }

    /**
     * Manage module promotion
     *
     * @param build AbstractBuild<?, ?>
     * @param notification GrapesNotification
     * @param client GrapesClient
     * @param user String
     * @param password String
     * @param logger PrintStream     @throws IOException
     */
    private void promoteModule(final AbstractBuild<?, ?> build, final GrapesNotification notification, final GrapesClient client, final String user, final String password, final PrintStream logger) {
        boolean sent = false;
        try {
            // If server is not reachable, let's postpone the notification
            if(!client.isServerAvailable()) {
                final ResendBuildAction resendAction = new ResendBuildAction(NotificationType.PROMOTE, null, notification.moduleName(), notification.moduleVersion());
                build.addAction(resendAction);
                logger.println("[GRAPES] WARNING: Grapes server is not reachable yet, notification has been postponed.");
                return;
            }

            client.promoteModule( notification.moduleName(), notification.moduleVersion(), user, password);
            sent = true;
            logger.println("[GRAPES] Module successfully promoted");

        } catch (Exception e) {
            logger.println("[GRAPES] The notification has been postpone due to an error.");
            GrapesPlugin.getLogger().log(Level.SEVERE, "[GRAPES] An error occurred! ", e);

        }
        finally {
            if(!sent){
                final ResendBuildAction resendAction = new ResendBuildAction(NotificationType.PROMOTE, null, notification.moduleName(), notification.moduleVersion());
                build.addAction(resendAction);
            }
        }
    }

    /**
     * Manage Module POST notification
     *
     * @param build AbstractBuild<?, ?>
     * @param notification GrapesNotification
     * @param client GrapesClient
     * @param user String
     * @param password String
     * @param logger PrintStream     @throws IOException
     * @throws InterruptedException
     */
    private void sendModule(final AbstractBuild<?, ?> build, GrapesNotification notification, final GrapesClient client, final String user, final String password, final PrintStream logger) throws IOException, InterruptedException {
        boolean sent = false;
        final FilePath moduleFilePath = notification.getMimePath();

        // No module file, it should be a configuration error
        if(!moduleFilePath.exists()){
            logger.println("[GRAPES] WARNING: Grapes Maven plugin report does not exist.");
            logger.println("[GRAPES] WARNING: Make sure that Grapes Jenkins plugin is still suppose to send Grapes Maven plugin reports.");
            return;
        }

        // Keep the Json file in the build history
        final FilePath reportFile = GrapesPlugin.getReportFolder(build);
        moduleFilePath.copyTo(reportFile);

        logger.println("[GRAPES] Grapes Maven plugin report detected.");
        final Module module = GrapesPlugin.getModule(new File(String.valueOf(moduleFilePath)));
        final AbstractProject<?, ?> project = build.getParent();

        // Post the module
        try {
            // If server is not reachable, let's postpone the notification
            if(!client.isServerAvailable()) {
                final ResendBuildAction resendAction = new ResendBuildAction(NotificationType.POST_MODULE, reportFile, module.getName(), module.getVersion());
                build.addAction(resendAction);
                logger.println("[GRAPES] WARNING: Grapes server is not reachable yet, notification has been postponed.");
                return;
            }
            
            client.postModule(module, user, password);
            sent = true;
            logger.println("[GRAPES] Module successfully sent");

            // Clean up previous ResendActions if any
            cleanUpResendAction(project, module);

            // Generate build action with the dependency report
            final GrapesBuildAction buildAction = new GrapesBuildAction(module, client);

            if (buildAction.isInitOk()) {
                build.addAction(buildAction);
                logger.println("[GRAPES] Report successfully built.");
            }

        } catch (Exception e) {
            logger.println("[GRAPES] The notification has been postpone due to an error.");
            GrapesPlugin.getLogger().log(Level.SEVERE, "[GRAPES] An error occurred! ", e);

        }
        finally {
            if(!sent){
                final ResendBuildAction resendAction = new ResendBuildAction(NotificationType.POST_MODULE, reportFile, module.getName(), module.getVersion());
                build.addAction(resendAction);
            }
        }

    }

    /**
     * Returns the current Grapes configuration of the job
     *
     * @return GrapesConfig
     */
    private GrapesConfig getConfig() {
        final GrapesNotifierDescriptor descriptor = (GrapesNotifierDescriptor) getDescriptor();
        return descriptor.getConfiguration(configName);
    }

    /**
     * Returns module to ResendBuildAction if it exist in Job's properties
     * @param project
     * @return
     */
    @Override
    public Collection<? extends Action> getProjectActions(final AbstractProject<?, ?> project) {
        final List<ResendBuildAction> resendBuildActions = new ArrayList<ResendBuildAction>();

        try {
            for (AbstractBuild<?, ?> build : project.getBuilds()) {
                for (ResendBuildAction resendBuildAction : build.getActions(ResendBuildAction.class)) {
                    if (resendBuildAction.toSend()) {
                        resendBuildActions.add(resendBuildAction);
                    }
                }
            }
        } catch (Exception e){
            GrapesPlugin.getLogger().log(Level.SEVERE, "[GRAPES] Failed to retrieve resend information : ", e);
        }

        if(resendBuildActions.isEmpty()){
            return Collections.emptyList();
        }

        final GrapesNotifierDescriptor descriptor = (GrapesNotifierDescriptor) getDescriptor();
        final GrapesConfig config = descriptor.getConfiguration(configName);
        return Collections.singletonList(new ResendProjectAction(resendBuildActions, config));
    }

    /**
     * Discard previous resend actions for a version of a module
     *
     * @param project AbstractProject<?, ?>
     * @param module Module
     */
    private void cleanUpResendAction(final AbstractProject<?, ?> project, final Module module) throws IOException {
        try {
            for (AbstractBuild<?, ?> build : project.getBuilds()) {
                for (ResendBuildAction resendAction : build.getActions(ResendBuildAction.class)) {
                    if (resendAction.getModuleName().equals(module.getName()) &&
                            resendAction.getModuleVersion().equals(module.getVersion())) {
                        resendAction.discard();
                    }
                }
            }
        } catch (Exception e){
            GrapesPlugin.getLogger().log(Level.SEVERE, "[GRAPES] Failed to discard resend information : ", e);
        }
    }


    /**
     * Descriptor for {@link GrapesNotifier}. Used as a singleton.
     *
     * <p>
     * See <tt>src/main/resources/org/axway/grapes/jenkins/GrapesNotifier/*.jelly</tt>
     * for the actual HTML fragment for the config screen.
     */
    @Extension
    public static final class GrapesNotifierDescriptor extends BuildStepDescriptor<Publisher> {

        private volatile List<GrapesConfig> servers;

        public GrapesNotifierDescriptor() {
            load();
        }

        public List<GrapesConfig> getServers() {
            return servers;
        }

        public void setServers(final List<GrapesConfig> servers) {
            this.servers = servers;
        }

        /**
         * Returns true if this task is applicable to the given project.
         * Limit the use of the notifier to maven jobs for now...
         *
         * @param jobType Class<? extends AbstractProject>
         * @return boolean
         */
        @Override
        public boolean isApplicable(@SuppressWarnings("rawtypes") final Class<? extends AbstractProject> jobType) {
            return AbstractMavenProject.class.isAssignableFrom(jobType) &&
                    servers != null && !servers.isEmpty();
        }

        /**
         * Returns a stored Grapes configuration regarding its name
         *
         * @param configName String
         * @return GrapesConfig
         */
        public GrapesConfig getConfiguration(final String configName) {
            for(GrapesConfig config : servers){
                if(config.getName().equals(configName)){
                    return config;
                }
            }

            GrapesPlugin.getLogger().severe("[GRAPES] No Grapes configuration for " + configName);
            return null;
        }

        @Override
        public boolean configure(final StaplerRequest req, final JSONObject json) {
            req.bindJSON(this, json);
            save();
            return true;
        }

        /**
         * Returns the display name of the task in the job config
         *
         * @return String
         */
        @Override
        public String getDisplayName() {
            return "Configure Grapes Notifications";
        }

        // Configuration Validation

        /**
         * Performs on-the-fly validation of the form field 'name'.
         *
         * @param value String This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         */
        public FormValidation doCheckName(@QueryParameter final String value) {
            if (value.length() == 0) {
                return FormValidation.error("Please set a name");
            }
            if (value.length() < 4) {
                return FormValidation.warning("Isn't the name too short?");
            }

            return FormValidation.ok();
        }

        /**
         * Performs on-the-fly validation of the form field 'host'.
         *
         * @param value String This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         */
        public FormValidation doCheckHost(@QueryParameter final String value) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.error("Please set a valid host");
            }
            return FormValidation.ok();
        }


        /**
         * Performs on-the-fly validation of the form field 'credentials'.
         *
         * @param host String
         * @param port String
         * @param timeout String
         * @return
         */
        public FormValidation doTestConnection(@QueryParameter final String host, @QueryParameter final String port, @QueryParameter final String timeout) {
            final GrapesClient client = new GrapesClient(host, port);

            if (client.isServerAvailable()) {
                return FormValidation.ok("Success.");
            }
            else {
                return FormValidation.warning("Server not reachable!");
            }

        }
    }
}
