/*
 * SearchConsumer.java
 *
 * Location: $URL$
 * 
 * Version: $Revision$
 * 
 * Date: $Date$
 *
 * Copyright (c) 2002-2007, Hewlett-Packard Company and Massachusetts
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

package org.dspace.search;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Logger;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.Site;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.event.Consumer;
import org.dspace.event.Event;

/**
 * Class for updating search indices from content events.
 * 
 * @version $Revision$
 */
public class SearchConsumer implements Consumer
{
    /** log4j logger */
    private static Logger log = Logger.getLogger(SearchConsumer.class);

    // collect Items, Collections, Communities that need indexing
    private Set<DSpaceObject> objectsToUpdate = null;

    // handles to delete since IDs are not useful by now.
    private Set<String> handlesToDelete = null;

    public void initialize() throws Exception
    {
        // No-op
    }

    /**
     * Consume a content event -- just build the sets of objects to add (new) to
     * the index, update, and delete.
     * 
     * @param ctx
     *            DSpace context
     * @param event
     *            Content event
     */
    public void consume(Context ctx, Event event) throws Exception
    {
        if (objectsToUpdate == null)
        {
            objectsToUpdate = new HashSet<DSpaceObject>();
            handlesToDelete = new HashSet<String>();
        }

        int st = event.getSubjectType();
        // ensure filter was properly constructed
        if (!(st == Constants.ITEM || st == Constants.BUNDLE
              || st == Constants.COLLECTION || st == Constants.COMMUNITY))
        {
            log
                    .warn("SearchConsumer should not have been given this kind of Subject in an event, skipping: "
                            + event.toString());
            return;
        }
 
        // Pick off the special case of text bundles - which have indexing
        // implications. If event subject is a Bundle and event was
        // Add or Remove, transform the event to be a Modify on the owning Item.
        // It's a new bitstream in the TEXT bundle which would change the index.
        if (st == Constants.BUNDLE)
        {
            int et = event.getEventType();
            Bundle bundle = Bundle.find(ctx, event.getSubjectID());
            if ((et == Event.ADD || et == Event.REMOVE) && bundle != null
                 && bundle.getName().equals("TEXT"))
            {
                Item item = bundle.getItems()[0];
                if (item != null)
                {
                    objectsToUpdate.add(item);
                }
                if (log.isDebugEnabled())
                {
                    log.debug("Transforming Bundle event into MODIFY of Item "
                              + item.getHandle());
                }
            }
            return;
        }

        switch (event.getEventType())
        {
        case Event.CREATE:
        case Event.MODIFY:
        case Event.MODIFY_METADATA:
            if (dso == null)
                log.warn(event.getEventTypeAsString() + " event, could not get object for "
                        + event.getSubjectTypeAsString() + " id="
                        + String.valueOf(event.getSubjectID())
                        + ", perhaps it has been deleted.");
            else
                objectsToUpdate.add(dso);
            break;
        case Event.DELETE:
            String detail = event.getDetail();
            if (detail == null)
                log.warn("got null detail on DELETE event, skipping it.");
            else
                handlesToDelete.add(detail);
            break;
        default:
            log
                    .warn("SearchConsumer should not have been given a event of type="
                            + event.getEventTypeAsString()
                            + " on subject="
                            + event.getSubjectTypeAsString());
                break;
        }
    }

    /**
     * Process sets of objects to add, update, and delete in index. Correct for
     * interactions between the sets -- e.g. objects which were deleted do not
     * need to be added or updated, new objects don't also need an update, etc.
     */
    public void end(Context ctx) throws Exception
    {
        
        if(objectsToUpdate != null && handlesToDelete != null)
        {
         
            // update the changed Items not deleted because they were on create list
            for (DSpaceObject iu : objectsToUpdate)
            {
                if (iu.getType() != Constants.ITEM || ((Item) iu).isArchived())
                {
                    // if handle is NOT in list of deleted objects, index it:
                    String hdl = iu.getHandle();
                    if (hdl != null && !handlesToDelete.contains(hdl))
                    {
                        try
                        {
                            DSIndexer.indexContent(ctx, iu);
                            if (log.isDebugEnabled())
                                log.debug("Indexed "
                                        + Constants.typeText[iu.getType()]
                                        + ", id=" + String.valueOf(iu.getID())
                                        + ", handle=" + hdl);
                        }
                        catch (Exception e)
                        {
                            log.error("Failed while indexing object: ", e);
                        }
                    }
                }
            }

            for (String hdl : handlesToDelete)
            {
                try
                {
                    DSIndexer.unIndexContent(ctx, hdl);
                    if (log.isDebugEnabled())
                        log.debug("UN-Indexed Item, handle=" + hdl);
                }
                catch (Exception e)
                {
                    log.error("Failed while UN-indexing object: " + hdl, e);
                }

            }

        }
        
        // "free" the resources
        objectsToUpdate = null;
        handlesToDelete = null;
    }

    public void finish(Context ctx) throws Exception
    {
        // No-op
    }
    
    /**
     * private methods
     */
    
    private static DSpaceObject find(Context ctx, int type, int id, int status)
            throws SQLException
    {
        switch (type)
        {
            case Constants.BUNDLE    : return Bundle.find(ctx, id, status);
            case Constants.ITEM      : return Item.find(ctx, id, status);
            case Constants.COLLECTION: return Collection.find(ctx, id, status);
            case Constants.COMMUNITY : return Community.find(ctx, id, status);
        }
        return null;
    }
}
