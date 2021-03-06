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
package org.eclipse.che.api.project.shared.dto;

import org.eclipse.che.api.core.model.project.type.Attribute;
import org.eclipse.che.dto.shared.DTO;

/**
 * Data transfer object (DTO) for org.eclipse.che.api.project.shared.AttributeDescription
 *
 * @author andrew00x
 */
@DTO
public interface AttributeDto extends Attribute {

    @Override
    String getName();

    AttributeDto withName(String name);

    @Override
    String getDescription();

    AttributeDto withDescription(String description);

    @Override
    boolean isRequired();

    AttributeDto withRequired(boolean required);

    @Override
    boolean isVariable();

    AttributeDto withVariable(boolean variable);

    @Override
    ValueDto getValue();

    AttributeDto withValue(ValueDto value);

}
