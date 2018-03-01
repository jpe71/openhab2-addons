/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.evohome.internal.api.models.v2.response;

import com.google.gson.annotations.SerializedName;

/**
 * Response model for the mode
 * 
 * @author Jasper van Zuijlen
 *
 */
public class Mode {

    @SerializedName("systemMode")
    public String systemMode;

    @SerializedName("canBePermanent")
    public boolean canBePermanent;

    @SerializedName("canBeTemporary")
    public boolean canBeTemporary;

    @SerializedName("timingMode")
    public String timingMode;

    @SerializedName("maxDuration")
    public String maxDuration;

    @SerializedName("timingResolution")
    public String timingResolution;

}