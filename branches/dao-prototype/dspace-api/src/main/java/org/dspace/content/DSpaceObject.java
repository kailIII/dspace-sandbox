/*
 * DSpaceObject.java
 *
 * Version: $Revision$
 *
 * Date: $Date$
 *
 * Copyright (c) 2002-2005, Hewlett-Packard Company and Massachusetts
 * Institute of Technology.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * - Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * - Neither the name of the Hewlett-Packard Company nor the name of the
 * Massachusetts Institute of Technology nor the names of their
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDERS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 * TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 */
package org.dspace.content;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.apache.log4j.Logger;
import org.dspace.content.uri.ExternalIdentifier;
import org.dspace.content.uri.ObjectIdentifier;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;

/**
 * Abstract base class for DSpace objects
 */
public abstract class DSpaceObject
{

    private static Logger log = Logger.getLogger(DSpaceObject.class);
    
    // accumulate information to add to "detail" element of content Event,
    // e.g. to document metadata fields touched, etc.
    private StringBuffer eventDetails = null;

    protected int id;
    protected UUID uuid;
    protected ObjectIdentifier oid;
    protected List<ExternalIdentifier> identifiers;
    
    /**
     * Reset the cache of event details.
     */
    protected void clearDetails()
    {
        eventDetails = null;
    }

    /**
     * Add a string to the cache of event details.  Automatically
     * separates entries with a comma.
     * Subclass can just start calling addDetails, since it creates
     * the cache if it needs to.
     * @param detail detail string to add.
     */
    protected void addDetails(String d)
    {
        if (eventDetails == null)
            eventDetails = new StringBuffer(d);
        else
            eventDetails.append(", ").append(d);
    }

    /**
     * @returns summary of event details, or null if there are none.
     */
    protected String getDetails()
    {
        return (eventDetails == null ? null : eventDetails.toString());
    }

    /**
     * Get the type of this object, found in Constants
     * 
     * @return type of the object
     */
    public abstract int getType();

    /**
     * Get the internal ID (database primary key) of this object
     * 
     * @return internal ID of object
     */
    public int getID()
    {
        return id;
    }

    public ObjectIdentifier getIdentifier()
    {
        return oid;
    }

    public void setIdentifier(ObjectIdentifier oid)
    {
        this.oid = oid;
    }

    /**
     * For those cases where you only want one, and you don't care what sort.
     */
    public ExternalIdentifier getExternalIdentifier()
    {
        if ((identifiers != null) && (identifiers.size() > 0))
        {
            return identifiers.get(0);
        }
        else
        {
            log.warn("no external identifiers found. type=" + getType() +
                    ", id=" + getID());
            return null;
        }
    }

    public List<ExternalIdentifier> getExternalIdentifiers()
    {
        if (identifiers == null)
        {
            identifiers = new ArrayList<ExternalIdentifier>();
        }

        return identifiers;
    }

    public void addExternalIdentifier(ExternalIdentifier identifier)
    {
        this.identifiers.add(identifier);
    }

    public void setExternalIdentifiers(List<ExternalIdentifier> identifiers)
    {
        this.identifiers = identifiers;
    }

    ////////////////////////////////////////////////////////////////////
    // Utility methods
    ////////////////////////////////////////////////////////////////////

    public String toString()
    {
        return ToStringBuilder.reflectionToString(this,
                ToStringStyle.MULTI_LINE_STYLE);
    }

    public boolean equals(Object o)
    {
        return EqualsBuilder.reflectionEquals(this, o);
    }

    public boolean equals(DSpaceObject other)
    {
        if (this.getType() == other.getType())
        {
            if (this.getID() == other.getID())
            {
                return true;
            }
        }

        return false;
    }

    public int hashCode()
    {
        return HashCodeBuilder.reflectionHashCode(this);
    }

    /**
     * Generic find for when the precise type of a DSO is not known, just the
     * a pair of type number and database ID.
     *
     * @param context - the context
     * @param type - type number
     * @param id - id within table of type'd objects
     * @return the object found, or null if it does not exist.
     * @throws SQLException only upon failure accessing the database.
     */
    public static DSpaceObject find(Context context, int type, int id)
        throws SQLException
    {
        switch (type)
        {
            case Constants.BITSTREAM : return Bitstream.find(context, id);
            case Constants.BUNDLE    : return Bundle.find(context, id);
            case Constants.ITEM      : return Item.find(context, id);
            case Constants.COLLECTION: return Collection.find(context, id);
            case Constants.COMMUNITY : return Community.find(context, id);
            case Constants.GROUP     : return Group.find(context, id);
            case Constants.EPERSON   : return EPerson.find(context, id);
            case Constants.SITE      : return Site.find(context, id);
        }
        return null;
    }
    
}
