/*
 * DSpaceAIPDisseminator.java
 *
 * Version: $Revision: 1.1 $
 *
 * Date: $Date: 2006/03/17 00:04:38 $
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

package org.dspace.content.packager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;

import org.apache.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.Bundle;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Site;
import org.dspace.core.Constants;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Context;
import org.dspace.license.CreativeCommons;

import edu.harvard.hul.ois.mets.Agent;
import edu.harvard.hul.ois.mets.AmdSec;
import edu.harvard.hul.ois.mets.BinData;
import edu.harvard.hul.ois.mets.Loctype;
import edu.harvard.hul.ois.mets.MdRef;
import edu.harvard.hul.ois.mets.MdWrap;
import edu.harvard.hul.ois.mets.Mdtype;
import edu.harvard.hul.ois.mets.Mets;
import edu.harvard.hul.ois.mets.MetsHdr;
import edu.harvard.hul.ois.mets.Name;
import edu.harvard.hul.ois.mets.RightsMD;
import edu.harvard.hul.ois.mets.Role;
import edu.harvard.hul.ois.mets.Div;
import edu.harvard.hul.ois.mets.Mptr;
import edu.harvard.hul.ois.mets.StructMap;
import edu.harvard.hul.ois.mets.Type;
import edu.harvard.hul.ois.mets.helper.Base64;
import edu.harvard.hul.ois.mets.helper.MetsException;
import edu.harvard.hul.ois.mets.helper.PCData;

/**
 * Subclass of the METS packager framework to disseminate a DSpace
 * Archival Information Package (AIP).  The AIP is intended to be, foremost,
 * a _complete_ and _accurate_ representation of one object in the DSpace
 * object model.  An AIP contains all of the information needed to restore
 * the object precisely in another DSpace archive instance.
 * <p>
 * This packager writes two distinct types of AIPs: "Internal" and "External".
 * The internal AIP, which is selected by specifying a PackageParameters
 * key "internal" with the value "true", is intended to preserve a complete
 * record of an object within its own archive, as a file in the asset store.
 * Thus, it can refer to other files in the asset store by location instead
 * of copying them into a "package", so its package format is simply a
 * METS XML document serialized into a file.
 * <p>
 * An "external" AIP (the default), is a conventional Zip-file based package
 * that includes copies of all bitstreams referenced by the object as well
 * as a serialized METS XML document in the path "mets.xml".
 *
 * Configuration keys:
 * The following take as values a space-and-or-comma-separated list
 * of plugin names that name *either* a DisseminationCrosswalk or
 * StreamDisseminationCrosswalk plugin.  Shown are the dfeault values.
 * The value may be a simple plugin name, or a METS MDsec-name followed by
 * a colon and the plugin name e.g. "DSpaceHistory :HISTORY"
 *
 *    # MD types to put in the sourceMD section of the object.
 *    aip.disseminate.sourceMD = AIP-TECHMD
 *
 *    # MD types to put in the techMD section of the object (and member Bitstreams if an Item)
 *    aip.disseminate.techMD = PREMIS
 *
 *    # MD types to put in digiprovMD section of the object.
 *    # (Note that this is disabled unless the History System is installed)
 *    #aip.disseminate.digiprovMD = DSpaceHistory :HISTORY
 *
 *    # MD types to put in the rightsMD section of the object.
 *    aip.disseminate.rightsMD = DSpaceDepositLicense:DSPACE_DEPLICENSE, \
 *       CreativeCommonsRDF:DSPACE_CCRDF, CreativeCommonsText:DSPACE_CCTXT
 *
 *    # MD types to put in dmdSec's corresponding  the object.
 *    aip.disseminate.dmd = MODS, DIM
 *
 * @author Larry Stone
 * @version $Revision: 1.1 $
 * @see AbstractMETSDisseminator
 */
public class DSpaceAIPDisseminator
    extends AbstractMETSDisseminator
{
    /** log4j category */
    private static Logger log = Logger.getLogger(DSpaceAIPDisseminator.class);

    /**
     * Unique identifier for the profile of the METS document.
     * To ensure uniqueness, it is the URL that the XML schema document would
     * have _if_ there were to be one.  There is no schema at this time.
     */
    public final static String PROFILE_1_0 =
        "http://www.dspace.org/schema/aip/mets_aip_1_0.xsd";

    /** TYPE of the div containing AIP's parent handle in its mptr. */
    final public static String PARENT_DIV_TYPE = "AIP Parent Link";

    // Default MDTYPE value for deposit license -- "magic string"
    // NOTE: format is  <label-for-METS>:<DSpace-crosswalk-name>
    private final static String DSPACE_DEPOSIT_LICENSE_MDTYPE =
        "DSpaceDepositLicense:DSPACE_DEPLICENSE";

    // Default MDTYPE value for CC license in RDF -- "magic string"
    // NOTE: format is  <label-for-METS>:<DSpace-crosswalk-name>
    private final static String CREATIVE_COMMONS_RDF_MDTYPE =
        "CreativeCommonsRDF:DSPACE_CCRDF";

    // Default MDTYPE value for CC license in Text -- "magic string"
    // NOTE: format is  <label-for-METS>:<DSpace-crosswalk-name>
    private final static String CREATIVE_COMMONS_TEXT_MDTYPE =
        "CreativeCommonsText:DSPACE_CCTXT";

    /**
     * Return identifier string for the METS profile this produces.
     *
     * @return string name of profile.
     */
    public String getProfile()
    {
        return PROFILE_1_0;
    }

    /**
     * Returns name of METS fileGrp corresponding to a DSpace bundle name.
     * For AIP the mapping is direct.
     * @param bname name of DSpace bundle.
     * @return string name of fileGrp
     */
    public String bundleToFileGrp(String bname)
    {
        return bname;
    }

    /**
     * metsHdr for AIP.
     * CREATEDATE is time at which the package (i.e. this manifest) was created.
     * LASTMODDATE is last-modified time of the target object, if available.
     * Agent describes the archive this belongs to.
     */
    public MetsHdr makeMetsHdr(Context context, DSpaceObject dso,
                               PackageParameters params)
    {
        MetsHdr metsHdr = new MetsHdr();

        // date the METS package/manifest was created.
        metsHdr.setCREATEDATE(new Date());

        if (dso.getType() == Constants.ITEM)
            metsHdr.setLASTMODDATE(((Item)dso).getLastModified());

        // Agent - name custodian, the DSpace Archive, by handle.
        Agent agent = new Agent();
        agent.setROLE(Role.CUSTODIAN);
        agent.setTYPE(Type.OTHER);
        agent.setOTHERTYPE("DSpace Archive");
        Name name = new Name();
        name.getContent()
                .add(new PCData(Site.getSiteHandle()));
        agent.getContent().add(name);
        metsHdr.getContent().add(agent);
        return metsHdr;
    }

    /**
     * Get DMD choice for Item.  It defaults to MODS, plus DIM.
     */
    public String [] getDmdTypes(Context context, DSpaceObject dso, PackageParameters params)
        throws SQLException, IOException, AuthorizeException
    {
        String dmdTypes = ConfigurationManager.getProperty("aip.disseminate.dmd");
        if (dmdTypes == null)
        {
            String result[] = new String[2];
            result[0] = "MODS";
            result[1] = "DIM";
            return result;
        }
        else
            return dmdTypes.split("\\s*,\\s*");
    }

    /**
     * Get name of technical metadata crosswalk for Bitstreams.
     * Default is PREMIS (for Bistreams only).
     * This is both the name of the crosswalk plugin
     * and the METS MDTYPE.
     */
    public String[] getTechMdTypes(Context context, DSpaceObject dso, PackageParameters params)
        throws SQLException, IOException, AuthorizeException
    {
        String techTypes = ConfigurationManager.getProperty("aip.disseminate.techMD");
        if (techTypes == null)
        {
            if (dso.getType() == Constants.BITSTREAM)
            {
                String result[] = new String[1];
                result[0] = "PREMIS";
                return result;
            }
            else
            {
                return new String[0];
            }
        }
        else
            return techTypes.split("\\s*,\\s*");
    }

    /**
     * Get name of source metadata crosswalk for each kind of DSO.
     * Default is AIP-TECHMD.
     * In an AIP, the sourceMD element MUST include the original persistent
     * identifier (Handle) of the object, and the original persistent ID
     * (Handle) of its parent in the archive, so that it can be restored.
     */
    public String[] getSourceMdTypes(Context context, DSpaceObject dso, PackageParameters params)
        throws SQLException, IOException, AuthorizeException
    {
        String sourceTypes = ConfigurationManager.getProperty("aip.disseminate.sourceMD");
        if (sourceTypes == null)
        {
            String result[] = new String[1];
            result[0] = "AIP-TECHMD";
            return result;
        }
        else
            return sourceTypes.split("\\s*,\\s*");
    }

    /**
     * Get name of provenance MD crosswalks - none by default.
     */
    public String[] getDigiprovMdTypes(Context context, DSpaceObject dso, PackageParameters params)
        throws SQLException, IOException, AuthorizeException
    {
        String dpTypes = ConfigurationManager.getProperty("aip.disseminate.digiprovMD");
        if (dpTypes == null)
            return new String[0];
        else
            return dpTypes.split("\\s*,\\s*");
    }

    /**
     * Return crosswalks of Rights metadata types.  By default, for Item
     * only, return the deposit license and CreativeCommons if available.
     */
    public String[] getRightsMdTypes(Context context, DSpaceObject dso, PackageParameters params)
        throws SQLException, IOException, AuthorizeException
    {

        // rights only apply to Item at this time.
        if (dso.getType() == Constants.ITEM)
        {
            String rTypes = ConfigurationManager.getProperty("aip.disseminate.rightsMD");
            if (rTypes == null)
            {
                List<String> result = new ArrayList<String>();
                if (PackageUtils.findDepositLicense(context, (Item)dso) != null)
                    result.add(DSPACE_DEPOSIT_LICENSE_MDTYPE);

                if (CreativeCommons.getLicenseRdfBitstream((Item)dso) != null)
                    result.add(CREATIVE_COMMONS_RDF_MDTYPE);
                else if (CreativeCommons.getLicenseTextBitstream((Item)dso) != null)
                    result.add(CREATIVE_COMMONS_TEXT_MDTYPE);
                return result.toArray(new String[result.size()]);
            }
            else
                return rTypes.split("\\s*,\\s*");
        }
        return new String[0];
    }

    /**
     * Get the URL by which the METS manifest refers to a Bitstream
     * member of an Item the "package".  Note that this ONLY has to work
     * for the Bitstreams belonging to a Bunde in an Item, NOT for the
     * other associated Bitstreams containing metadata streams, or logo
     * of a Community/Collection, etc.
     * <p>
     * For an internal AIP, this is a reference to a file
     * in the asset store.  An external AIP names a file in the package
     * with a relative URL, that is, relative pathname.
     * <p>
     * @return String in URL format naming path to bitstream.
     */
    public String makeBitstreamURL(Bitstream bitstream, PackageParameters params)
    {
        // if bare manifest, use external "persistent" URI for bitstreams
        if (params != null && (params.getBooleanProperty("manifestOnly", false) ||
            params.getBooleanProperty("internal", false)))
        {
            return bitstream.getAbsoluteURI().toString();
        }
        else
        {
            String base = "bitstream_"+String.valueOf(bitstream.getID());
            String ext[] = bitstream.getFormat().getExtensions();
            return (ext.length > 0) ? base+"."+ext[0] : base;
        }
    }

    /**
     * Adds another structMap element to contain the "parent link" that
     * is an essential part of every AIP.  This is a structmap of one
     * div, which contains an mptr indicating the Handle of the parent
     * of this object in the archive.  The div has a unique TYPE attribute
     * value, "AIP Parent Link", and the mptr has a LOCTYPE of "HANDLE"
     * and an xlink:href containing the raw Handle value.
     * <p>
     * Note that the parent Handle has to be stored here because the
     * parent is needed to create a DSpace Object when restoring the
     * AIP; it cannot be determined later once the ingester parses it
     * out of the metadata when the crosswalks are run.  So, since the
     * crosswalks require an object to operate on, and creating the
     * object requires a parent, we cannot depend on metadata processed
     * by crosswalks (e.g.  AIP techMd) for the parent, it has to be at
     * a higher level in the AIP manifest.  The structMap is an obvious
     * and standards-compliant location for it.
     */
    public void addStructMap(Context context, DSpaceObject dso,
                               PackageParameters params, Mets mets)
        throws SQLException, IOException, AuthorizeException, MetsException
    {
        // find parent Handle
        String parentHandle = null;
        switch (dso.getType())
        {
            case Constants.ITEM:
                parentHandle = ((Item)dso).getOwningCollection().getHandle();
                break;

            case Constants.COLLECTION:
                parentHandle = (((Collection)dso).getCommunities())[0].getHandle();
                break;

            case Constants.COMMUNITY:
                Community parent = ((Community)dso).getParentCommunity();
                if (parent == null)
                    parentHandle = Site.getSiteHandle();
                else
                    parentHandle = parent.getHandle();
        }

        // add a structMap to contain div pointing to parent:
        StructMap structMap = new StructMap();
        structMap.setID(gensym("struct"));
        structMap.setTYPE("LOGICAL");
        structMap.setLABEL("Parent");
        Div div0 = new Div();
        div0.setID(gensym("div"));
        div0.setTYPE(PARENT_DIV_TYPE);
        div0.setLABEL("Parent of this DSpace Object");
        Mptr mptr = new Mptr();
        mptr.setID(gensym("mptr"));
        mptr.setLOCTYPE(Loctype.HANDLE);
        mptr.setXlinkHref(parentHandle);
        div0.getContent().add(mptr);
        structMap.getContent().add(div0);
        mets.getContent().add(structMap);
    }

    /**
     * include all bundles in AIP as content.
     */
    public boolean includeBundle(Bundle bundle)
    {
        return true;
    }
}
