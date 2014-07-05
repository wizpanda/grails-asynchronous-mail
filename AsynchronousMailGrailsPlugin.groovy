import grails.plugin.asyncmail.AsynchronousMailJob
import grails.plugin.asyncmail.AsynchronousMailMessageBuilderFactory
import grails.plugin.asyncmail.ExpiredMessagesCollectorJob
import grails.plugin.mail.MailService
import grails.plugins.quartz.*
import grails.util.Environment
import org.codehaus.groovy.grails.commons.spring.GrailsApplicationContext

class AsynchronousMailGrailsPlugin {
    def version = "1.1-RC"
    def grailsVersion = "2.3.1 > *"
    def loadAfter = ['mail', 'quartz', 'hibernate', 'hibernate4']
    def pluginExcludes = [
            "grails-app/conf/DataSource.groovy",
            "grails-app/i18n/**",
            "grails-app/views/test/**",
            "web-app/WEB-INF/**",
            "web-app/images/**",
            "web-app/js/**",
            "web-app/css/errors.css",
            "web-app/css/main.css",
            "web-app/css/mobile.css"
    ]

    def author = "Vitalii Samolovskikh aka Kefir"
    def authorEmail = "kefirfromperm@gmail.com"
    def title = "Asynchronous Mail Plugin"
    def description = 'The plugin realises asynchronous mail sending. ' +
            'It stores messages in the DB and sends them asynchronously by the quartz job.'
    def documentation = "http://www.grails.org/plugin/asynchronous-mail"

    String license = 'APACHE'
    def issueManagement = [system: 'JIRA', url: 'http://jira.grails.org/browse/GPASYNCHRONOUSMAIL']
    def scm = [url: 'https://github.com/kefirfromperm/grails-asynchronous-mail']

    def doWithSpring = {
        loadAsyncMailConfig(application.config)

        // The mail service from Mail plugin
        nonAsynchronousMailService(MailService) {
            mailMessageBuilderFactory = ref("mailMessageBuilderFactory")
            grailsApplication = ref("grailsApplication")
        }

        asynchronousMailMessageBuilderFactory(AsynchronousMailMessageBuilderFactory) {
            it.autowire = true
        }
    }

    def doWithApplicationContext = { GrailsApplicationContext applicationContext ->
        // Register alias for the asynchronousMailService
        applicationContext.registerAlias('asynchronousMailService', 'asyncMailService')

        // Configure sendMail methods
        configureSendMail(application, applicationContext)

        // Starts jobs
        startJobs(application, applicationContext)
    }

    def onChange = { event ->
        // Configure sendMail methods
        configureSendMail(application, (GrailsApplicationContext) event.ctx)
    }

    /**
     * Start send job and messages collector
     */
    def startJobs(application, applicationContext) {
        def asyncMailConfig = application.config.asynchronous.mail
        if (!asyncMailConfig.disable) {
            JobManagerService jobManagerService = applicationContext.jobManagerService

            List<JobDescriptor> jobDescriptors = jobManagerService.getJobs("AsynchronousMail")



            def sjd = jobDescriptors.find { it.name == 'grails.plugin.asyncmail.AsynchronousMailJob' }
            if(!sjd?.triggerDescriptors) {
                AsynchronousMailJob.schedule((Long) asyncMailConfig.send.repeat.interval)
            }


            def cjd = jobDescriptors.find { it.name == 'grails.plugin.asyncmail.ExpiredMessagesCollectorJob' }
            if(!cjd.triggerDescriptors){
                ExpiredMessagesCollectorJob.schedule((Long) asyncMailConfig.expired.collector.repeat.interval)
            }
        }
    }

    /**
     * Configure sendMail methods
     */
    static configureSendMail(application, GrailsApplicationContext applicationContext){
        def asyncMailConfig = application.config.asynchronous.mail

        // Override the mailService
        if (asyncMailConfig.override) {
            applicationContext.mailService.metaClass*.sendMail = { Closure callable ->
                applicationContext.asynchronousMailService?.sendAsynchronousMail(callable)
            }
        } else {
            applicationContext.asynchronousMailService.metaClass*.sendMail = { Closure callable ->
                applicationContext.asynchronousMailService?.sendAsynchronousMail(callable)
            }
        }
    }

    /**
     * Loads the asynchronous mail configuration.
     *
     * 1. Loads the grails configuration.
     * 2. Merges it with the default asynchronous mail configuration.
     * 3. Merges it with the user asynchronous mail configuration.
     *
     * http://swestfall.blogspot.co.uk/2011/08/grails-plugins-and-default-configs.html
     */
    private void loadAsyncMailConfig(def config) {
        GroovyClassLoader classLoader = new GroovyClassLoader(getClass().classLoader)
        // merging default config into main application config
        ConfigObject currentAsyncConfig = config.asynchronous.mail
        ConfigObject defaultAsyncConfig = new ConfigSlurper(Environment.current.name)
                .parse(classLoader.loadClass('DefaultAsynchronousMailConfig'))

        ConfigObject newAsyncConfig = new ConfigObject()
        newAsyncConfig.putAll( defaultAsyncConfig.asynchronous.mail.merge(currentAsyncConfig))

        config.asynchronous.mail = newAsyncConfig

        // merging user-defined config into main application config if provided
        try {
            config.merge(new ConfigSlurper(Environment.current.name).parse(
                    classLoader.loadClass('AsynchronousMailConfig'))
            )
        } catch (Exception ignored) {
            // ignore, just use the defaults
        }
    }
}
