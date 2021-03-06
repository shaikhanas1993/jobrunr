package org.jobrunr.jobs.details;

import org.jobrunr.jobs.JobDetails;
import org.jobrunr.jobs.details.instructions.ALoadOperandInstruction;
import org.jobrunr.jobs.details.instructions.InvokeSpecialInstruction;
import org.jobrunr.jobs.details.instructions.InvokeVirtualInstruction;
import org.jobrunr.jobs.details.instructions.LdcInstruction;
import org.jobrunr.stubs.TestService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.jobrunr.JobRunrAssertions.assertThat;
import static org.jobrunr.jobs.details.JobDetailsGeneratorUtils.toFQClassName;

class JobDetailsFinderContextTest {

    @Test
    void reproduceIssueStringBuilderAppend() {
        final JobDetailsFinderContext jobDetailsFinderContext = getJobDetailsFinderContext();
        new ALoadOperandInstruction(jobDetailsFinderContext).load(1);
        new InvokeSpecialInstruction(jobDetailsFinderContext).load("java/lang/StringBuilder", "<init>", "()V", false);
        new LdcInstruction(jobDetailsFinderContext).load("Hello ");
        new InvokeVirtualInstruction(jobDetailsFinderContext).load("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        new ALoadOperandInstruction(jobDetailsFinderContext).load(0);
        new InvokeVirtualInstruction(jobDetailsFinderContext).load("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        new InvokeVirtualInstruction(jobDetailsFinderContext).load("java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        new InvokeVirtualInstruction(jobDetailsFinderContext).load("org/jobrunr/stubs/TestService", "doWork", "(Ljava/lang/String;)V", false);

        final JobDetails jobDetails = jobDetailsFinderContext.getJobDetails();
        assertThat(jobDetails)
                .hasClass(TestService.class)
                .hasMethodName("doWork")
                .hasArgs("Hello World");
    }

    private JobDetailsFinderContext getJobDetailsFinderContext() {
        return new JobDetailsFinderContext(null) {

            @Override
            protected void init() {
                this.setClassName(toFQClassName("org/jobrunr/examples/webapp/api/JobController"));
                this.setMethodName("lambda$simpleJob$4ffb5ff$1");
                this.setJobParameters(new ArrayList<>());
            }

            @Override
            protected List<Object> initLocalVariables(Object[] params) {
                return Arrays.asList("World", null);
            }
        };
    }

}