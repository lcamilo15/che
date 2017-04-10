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
package org.eclipse.che.api.project.server;

import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.ForbiddenException;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.notification.EventService;
import org.eclipse.che.api.core.notification.EventSubscriber;
import org.eclipse.che.api.project.server.notification.WorkingCopyChangedEvent;
import org.eclipse.che.api.project.shared.dto.TextChangeDto;
import org.eclipse.che.api.project.shared.dto.event.FileTrackingOperationDto;
import org.eclipse.che.api.vfs.impl.file.event.detectors.FileTrackingOperationEvent;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.io.IOException;

import static org.eclipse.che.api.project.shared.Constants.CHE_DIR;

/**
 *
 *
 * @author Roman Nikitenko
 */
@Singleton
public class EditorWorkingCopyManager {
    private static final String TEMP_DIR        = "/" + CHE_DIR + "/temp";

    private final ProjectManager     projectManager;

    @Inject
    public EditorWorkingCopyManager(ProjectManager projectManager,
                                    EventService eventService) {
        this.projectManager = projectManager;

        eventService.subscribe(new EventSubscriber<WorkingCopyChangedEvent>() {
            @Override
            public void onEvent(WorkingCopyChangedEvent event) {
                onWorkingCopyChanged(event.getTextChange());
            }
        });

        eventService.subscribe(new EventSubscriber<FileTrackingOperationEvent>() {
            @Override
            public void onEvent(FileTrackingOperationEvent event) {
                onFileOperation(event.getFileTrackingOperation());
            }
        });
    }

    private void onFileOperation(FileTrackingOperationDto operation) {
        try {
            FileTrackingOperationDto.Type type = operation.getType();
            String path = operation.getPath();
            String projectPath = projectManager.asFile(path).getProject();

            switch (type) {
                case START: {
                    VirtualFileEntry workingCopy = getWorkingCopy(path, projectPath);
                    if (workingCopy != null) {
                        System.out.println("*********** working copy already exist !!! ");
                        //TODO check hashes of contents for working copy and base file
                    } else {
                        System.out.println("***********  CREATE working copy");
                        createWorkingCopy(path, projectPath);
                    }
                    break;
                }
                case STOP: {
                    //TODO check hashes of contents for working copy and base file
                    VirtualFileEntry workingCopy = getWorkingCopy(path, projectPath);
                    System.out.println("***********  REMOVE working copy");
                    workingCopy.remove();
                    break;
                }
                case SUSPEND: {


                    break;
                }
                case RESUME: {


                    break;
                }
                case MOVE: {
                    String oldPath = operation.getOldPath();
                    VirtualFileEntry workingCopy = getWorkingCopy(oldPath, projectPath);

                    String newName = getWorkingCopyFileName(path);
                    workingCopy.getVirtualFile().rename(newName);

                    break;
                }
                default: {


                    break;
                }
            }

        } catch (NotFoundException | ServerException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ForbiddenException e) {
            e.printStackTrace();
        } catch (ConflictException e) {
            e.printStackTrace();
        }
    }

    private void onWorkingCopyChanged(TextChangeDto change) {
        try {
            //TODO handle rename file

            String filePath = change.getFileLocation();
            String projectPath = change.getProjectPath();
            String text = change.getText();
            int offset = change.getOffset();
            int removedCharCount = change.getRemovedCharCount();
            VirtualFileEntry workingCopy = getWorkingCopy(filePath, projectPath);
            if (workingCopy == null) {
                workingCopy = createWorkingCopy(filePath, projectPath);
            }

            String newContent = null;
            String oldContent = workingCopy.getVirtualFile().getContentAsString();
            if (text != null && !text.isEmpty()) {
                newContent = new StringBuilder(oldContent).insert(offset, text).toString();
            } else if (removedCharCount > 0) {
                newContent = new StringBuilder(oldContent).delete(offset, offset + removedCharCount).toString();
            }

            if (newContent != null) {
                //TODO
                System.out.println("***************** update workingCopy ");
                workingCopy.getVirtualFile().updateContent(newContent);
            }
        } catch (Exception e) {
            //TODO handle exception
            System.out.println(e.getMessage());

        }
    }

    private VirtualFileEntry createWorkingCopy(String filePath, String projectPath)
            throws NotFoundException, ServerException, ConflictException, ForbiddenException, IOException {
        FileEntry file = projectManager.asFile(filePath);//TODO when file null???

        String workingCopyPath = getWorkingCopyFileName(filePath);
        FolderEntry workingCopiesStorage = getWorkingCopiesStorage(projectPath);

        return workingCopiesStorage.createFile(workingCopyPath, file.getInputStream());
    }

    private VirtualFileEntry getWorkingCopy(String filePath, String projectPath)
            throws NotFoundException, ServerException, ConflictException, ForbiddenException, IOException {
        FolderEntry workingCopiesStorage = getWorkingCopiesStorage(projectPath);
        String workingCopyPath = getWorkingCopyFileName(filePath);

        return workingCopiesStorage.getChild(workingCopyPath);
    }

    private FolderEntry getWorkingCopiesStorage(String projectPath)
            throws NotFoundException, ServerException, ConflictException, ForbiddenException {
        RegisteredProject project = projectManager.getProject(projectPath);
        FolderEntry baseFolder = project.getBaseFolder();
        if (baseFolder == null) {
            throw new NotFoundException("Base folder not found for " + projectPath);
        }

        String tempDirectoryPath = baseFolder.getPath().toString() + TEMP_DIR;
        FolderEntry workingCopiesStorage = projectManager.asFolder(tempDirectoryPath);
        return workingCopiesStorage != null ? workingCopiesStorage : baseFolder.createFolder(TEMP_DIR);
    }

    private String getWorkingCopyFileName(String path) {
        if (path.startsWith("/")) {
            path = path.substring(1, path.length());
        }
        return path.replace('/', '.');
    }
}
