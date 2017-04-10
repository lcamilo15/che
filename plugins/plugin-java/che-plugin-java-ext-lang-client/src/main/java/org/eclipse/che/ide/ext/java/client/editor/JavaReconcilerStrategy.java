/*******************************************************************************
 * Copyright (c) 2012-2017 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.ide.ext.java.client.editor;

import com.google.common.base.Optional;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.google.web.bindery.event.shared.EventBus;

import org.eclipse.che.ide.api.editor.EditorWithErrors;
import org.eclipse.che.ide.api.editor.annotation.AnnotationModel;
import org.eclipse.che.ide.api.editor.document.Document;
import org.eclipse.che.ide.api.editor.reconciler.DirtyRegion;
import org.eclipse.che.ide.api.editor.reconciler.ReconcilingStrategy;
import org.eclipse.che.ide.api.editor.text.Region;
import org.eclipse.che.ide.api.editor.text.RegionImpl;
import org.eclipse.che.ide.api.editor.texteditor.TextEditor;
import org.eclipse.che.ide.api.resources.Project;
import org.eclipse.che.ide.api.resources.Resource;
import org.eclipse.che.ide.api.resources.VirtualFile;
import org.eclipse.che.ide.dto.DtoFactory;
import org.eclipse.che.ide.ext.java.client.JavaLocalizationConstant;
import org.eclipse.che.ide.ext.java.client.editor.JavaReconcileOperationEvent.ReconcileOperationHandler;
import org.eclipse.che.ide.ext.java.shared.dto.HighlightedPosition;
import org.eclipse.che.ide.ext.java.shared.dto.Problem;
import org.eclipse.che.api.project.shared.dto.TextChangeDto;
import org.eclipse.che.ide.ext.java.shared.dto.ReconcileResult;
import org.eclipse.che.ide.jsonrpc.RequestTransmitter;
import org.eclipse.che.ide.project.ResolvingProjectStateHolder;
import org.eclipse.che.ide.project.ResolvingProjectStateHolder.ResolvingProjectState;
import org.eclipse.che.ide.project.ResolvingProjectStateHolder.ResolvingProjectStateListener;
import org.eclipse.che.ide.project.ResolvingProjectStateHolderRegistry;
import org.eclipse.che.ide.util.UUID;
import org.eclipse.che.ide.util.loging.Log;

import javax.validation.constraints.NotNull;
import java.util.Collections;
import java.util.List;

import static org.eclipse.che.ide.api.editor.reconciler.DirtyRegion.REMOVE;
import static org.eclipse.che.ide.ext.java.client.util.JavaUtil.resolveFQN;
import static org.eclipse.che.ide.project.ResolvingProjectStateHolder.ResolvingProjectState.IN_PROGRESS;

public class JavaReconcilerStrategy implements ReconcilingStrategy, ResolvingProjectStateListener, ReconcileOperationHandler {
    private String ID;

    private final TextEditor                          editor;
    private final JavaCodeAssistProcessor             codeAssistProcessor;
    private final AnnotationModel                     annotationModel;
    private final DtoFactory                          dtoFactory;
    private final SemanticHighlightRenderer           highlighter;
    private final ResolvingProjectStateHolderRegistry resolvingProjectStateHolderRegistry;
    private final JavaLocalizationConstant            localizationConstant;
    private final RequestTransmitter                  requestTransmitter;
    private final EventBus eventBus;

    private EditorWithErrors            editorWithErrors;
    private ResolvingProjectStateHolder resolvingProjectStateHolder;

    @AssistedInject
    public JavaReconcilerStrategy(@Assisted @NotNull final TextEditor editor,
                                  @Assisted final JavaCodeAssistProcessor codeAssistProcessor,
                                  @Assisted final AnnotationModel annotationModel,
                                  RequestTransmitter requestTransmitter,
                                  final DtoFactory dtoFactory,
                                  final SemanticHighlightRenderer highlighter,
                                  final ResolvingProjectStateHolderRegistry resolvingProjectStateHolderRegistry,
                                  final JavaLocalizationConstant localizationConstant,
                                  EventBus eventBus) {
        this.requestTransmitter = requestTransmitter;
        this.eventBus = eventBus;
        this.ID = UUID.uuid(4);
//        Log.error(getClass(), "********************************************** Constructor  STRATEGY " + ID);
        this.editor = editor;
        this.codeAssistProcessor = codeAssistProcessor;
        this.annotationModel = annotationModel;
        this.dtoFactory = dtoFactory;
        this.highlighter = highlighter;
        this.resolvingProjectStateHolderRegistry = resolvingProjectStateHolderRegistry;
        this.localizationConstant = localizationConstant;
        if (editor instanceof EditorWithErrors) {
            this.editorWithErrors = ((EditorWithErrors)editor);
        }
        eventBus.addHandler(JavaReconcileOperationEvent.TYPE, this);
    }

    @Override
    public void setDocument(final Document document) {
        highlighter.init(editor.getEditorWidget(), document);

        if (getFile() instanceof Resource) {
            final Optional<Project> project = ((Resource)getFile()).getRelatedProject();

            if (!project.isPresent()) {
                return;
            }

            String projectType = project.get().getType();
            resolvingProjectStateHolder = resolvingProjectStateHolderRegistry.getResolvingProjectStateHolder(projectType);
            if (resolvingProjectStateHolder == null) {
                return;
            }
            resolvingProjectStateHolder.addResolvingProjectStateListener(this);

            if (resolvingProjectStateHolder.getState() == IN_PROGRESS) {
                disableReconciler(localizationConstant.codeAssistErrorMessageResolvingProject());
            }
        }
    }

    @Override
    public void reconcile(final DirtyRegion dirtyRegion, final Region subRegion) {
        parse(dirtyRegion);
    }

    @Override
    public void reconcile(final Region partition) {
        parse(partition);
    }

    public void parse() {
        final Region region = new RegionImpl(0, editor.getDocument().getContents().length());
        parse(region);
    }

    private void parse(final DirtyRegion dirtyRegion) {
        final VirtualFile file = getFile();

        if (file instanceof Resource) {
            final Project project = ((Resource)file).getProject();

            if (!project.exists()) {
                return;
            }


            //TODO handle illegal argument exception
            final String fqn = resolveFQN(file);
            final String projectPath = project.getPath();
            final int length = dirtyRegion.getLength();
            final TextChangeDto change = dtoFactory.createDto(TextChangeDto.class)
                                                .withWorkingCopyOwnerID(ID)
                                                .withProjectPath(projectPath)
                                                .withFileLocation(fqn)
                                                .withOffset(dirtyRegion.getOffset())
                                                .withText(dirtyRegion.getText());
            if (REMOVE.equals(dirtyRegion.getType())) {
                change.setRemovedCharCount(length);
            } else {
                change.setLength(length);
            }

//            Log.error(getClass(), "====///////////////////////////  before transmit dirty region " + ID);
//            Log.error(getClass(), "====///////////////////////////  before transmit dirty change removed " + change.getRemovedCharCount());
//            Log.error(getClass(), "====///////////////////////////  before transmit dirty region " + ID);
//            requestTransmitter.transmitOneToNone("ws-agent", "track:java-editor-reconcile-operation", change);
        }
    }

    private void parse(Region region) {
        final VirtualFile file = getFile();
        if (file instanceof Resource) {
            final Project project = ((Resource)file).getProject();

            if (!project.exists()) {
                return;
            }

            final String fqn = resolveFQN(file);
            final TextChangeDto change = dtoFactory.createDto(TextChangeDto.class)
                                                .withWorkingCopyOwnerID(ID)
                                                .withProjectPath(project.getPath())
                                                .withFileLocation(fqn)
                                                .withOffset(region.getOffset())
                                                .withLength(region.getLength())
                    .withText(editor.getDocument().getContents());

//            Log.error(getClass(), "====///////////////////////////  before transmit whole document " + ID);
//            requestTransmitter.transmitOneToNone("ws-agent", "track:java-editor-reconcile-operation", change);
        }
    }

    public VirtualFile getFile() {
        return editor.getEditorInput().getFile();
    }

    private void doReconcile(final List<Problem> problems) {
//        Log.error(getClass(), "do reconcile "  + ID + " /// "+ problems.size());
        if (this.annotationModel == null) {
            return;
        }
        ProblemRequester problemRequester;
        if (this.annotationModel instanceof ProblemRequester) {
            problemRequester = (ProblemRequester)this.annotationModel;
            problemRequester.beginReporting();
        } else {
            if (editorWithErrors != null) {
                editorWithErrors.setErrorState(EditorWithErrors.EditorState.NONE);
            }
            return;
        }
        try {
            boolean error = false;
            boolean warning = false;
            for (Problem problem : problems) {
                if (problem != null) {
//                    Log.error(getClass(), "**** current problem " + ID + " /// " + problem.getMessage());
                }

                if (!error) {
                    error = problem.isError();
                }
                if (!warning) {
                    warning = problem.isWarning();
                }
                problemRequester.acceptProblem(problem);
            }
            if(editorWithErrors != null) {
                if (error) {
                    editorWithErrors.setErrorState(EditorWithErrors.EditorState.ERROR);
                } else if (warning) {
                    editorWithErrors.setErrorState(EditorWithErrors.EditorState.WARNING);
                } else {
                    editorWithErrors.setErrorState(EditorWithErrors.EditorState.NONE);
                }
            }
        } catch (final Exception e) {
            Log.error(getClass(), e);
        } finally {
            problemRequester.endReporting();
        }
    }

    private void disableReconciler(String errorMessage) {
        codeAssistProcessor.disableCodeAssistant(errorMessage);
        doReconcile(Collections.<Problem>emptyList());
        highlighter.reconcile(Collections.<HighlightedPosition>emptyList());
    }

    @Override
    public void closeReconciler() {
        if (resolvingProjectStateHolder != null) {
            resolvingProjectStateHolder.removeResolvingProjectStateListener(this);
        }
    }

    @Override
    public void onResolvingProjectStateChanged(ResolvingProjectState state) {
        switch (state) {
            case IN_PROGRESS:
                disableReconciler(localizationConstant.codeAssistErrorMessageResolvingProject());
                break;
            case RESOLVED:
                parse();
                break;
            default:
                break;
        }
    }

    @Override
    public void onReconcileOperation(ReconcileResult reconcileResult) {
        Log.error(getClass(), "========= result " + reconcileResult.getWorkingCopyOwnerID() + "/// ");

        if (!editor.getEditorInput().getFile().getLocation().toString().equals(reconcileResult.getWorkingCopyOwnerID())) {
//            Log.error(getClass(), "========= return " + ID);
            return;
        }

//        Log.error(getClass(), "========= NOT return " + ID);
//        Log.error(getClass(), "========= reconcile problems  " + reconcileResult.getProblems().size());

        if (resolvingProjectStateHolder != null && resolvingProjectStateHolder.getState() == IN_PROGRESS) {
            disableReconciler(localizationConstant.codeAssistErrorMessageResolvingProject());
            return;
        } else {
            codeAssistProcessor.enableCodeAssistant();
        }

        doReconcile(reconcileResult.getProblems());
        highlighter.reconcile(reconcileResult.getHighlightedPositions());
    }
}
