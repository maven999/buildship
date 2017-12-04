/*
 * Copyright (c) 2015 the original author or authors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.eclipse.buildship.core.util.progress;


import java.util.concurrent.TimeUnit;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;

import org.eclipse.buildship.core.CorePlugin;
import org.eclipse.buildship.core.workspace.GradleBuild;
import org.eclipse.buildship.core.workspace.NewProjectHandler;

/**
 * Base job to execute project synchronization.
 *
 * @author Donat Csikos
 */
public abstract class SynchronizationJob extends GradleJob {

    private final Iterable<GradleBuild> gradleBuilds;
    private final NewProjectHandler newProjectHandler;
    private final AsyncHandler initializer;

    public SynchronizationJob(GradleBuild gradleBuild) {
        this(NewProjectHandler.NO_OP, gradleBuild);
    }

    public SynchronizationJob(Iterable<GradleBuild> gradleBuilds) {
        this(NewProjectHandler.NO_OP, gradleBuilds);
    }

    public SynchronizationJob(NewProjectHandler newProjectHandler, GradleBuild gradleBuild) {
        this(newProjectHandler, AsyncHandler.NO_OP, gradleBuild);
    }

    public SynchronizationJob(NewProjectHandler newProjectHandler, Iterable<GradleBuild> gradleBuilds) {
        this(newProjectHandler, AsyncHandler.NO_OP, gradleBuilds);
    }

    public SynchronizationJob(NewProjectHandler newProjectHandler, AsyncHandler initializer, GradleBuild gradleBuild) {
        this(newProjectHandler, initializer, ImmutableSet.of(gradleBuild));
    }

    public SynchronizationJob(NewProjectHandler newProjectHandler, AsyncHandler initializer, Iterable<GradleBuild> gradleBuilds) {
        super("Synchronize Gradle projects with workspace");
        this.newProjectHandler = newProjectHandler;
        this.initializer = initializer;
        this.gradleBuilds = ImmutableSet.copyOf(gradleBuilds);

    }

    public Iterable<GradleBuild> getGradleBuilds() {
        return this.gradleBuilds;
    }

    @Override
    public final IStatus run(final IProgressMonitor monitor) {
        final IProgressMonitor efficientMonitor = new RateLimitingProgressMonitor(monitor, 500, TimeUnit.MILLISECONDS);
        synchronize(efficientMonitor);
        return Status.OK_STATUS;
    }

    private void synchronize(IProgressMonitor monitor)  {
        final SubMonitor progress = SubMonitor.convert(monitor, ImmutableSet.copyOf(this.gradleBuilds).size() + 1);

        try {
            this.initializer.run(progress.newChild(1), getToken());

            for (GradleBuild build : this.gradleBuilds) {
                if (monitor.isCanceled()) {
                    throw new OperationCanceledException();
                }
                build.synchronize(this.newProjectHandler, this.initializer, getToken(), progress.newChild(1));
            }
        } catch (Exception e) {
            handleStatus(ToolingApiStatus.from("Synchronize Gradle projects with workspace", e));
        }
    }

    protected abstract void handleStatus(ToolingApiStatus status);

    /**
     * A {@link SynchronizationJob} is only scheduled if there is not already another one that
     * fully covers it.
     * <p/>
     * A job A fully covers a job B if all of these conditions are met:
     * <ul>
     * <li>A synchronizes the same Gradle builds as B</li>
     * <li>A and B have the same {@link NewProjectHandler} or B's {@link NewProjectHandler} is a
     * no-op</li>
     * <li>A and B have the same {@link AsyncHandler} or B's {@link AsyncHandler} is a no-op</li>
     * </ul>
     */
    @Override
    public boolean shouldSchedule() {
        for (Job job : Job.getJobManager().find(CorePlugin.GRADLE_JOB_FAMILY)) {
            if (job instanceof SynchronizationJob && isCoveredBy((SynchronizationJob) job)) {
                return false;
            }
        }
        return true;
    }

    private boolean isCoveredBy(SynchronizationJob other) {
        return Objects.equal(this.gradleBuilds, other.gradleBuilds) && (this.newProjectHandler == NewProjectHandler.NO_OP || Objects.equal(this.newProjectHandler, other.newProjectHandler))
                && (this.initializer == AsyncHandler.NO_OP || Objects.equal(this.initializer, other.initializer));
    }
}