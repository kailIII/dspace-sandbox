/*
 * MediaFilterManager.java
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

package org.dspace.app.mediafilter;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.MissingArgumentException;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;

import org.dspace.authorize.AuthorizeManager;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DCDate;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.ItemIterator;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.core.PluginManager;
import org.dspace.core.SelfNamedPlugin;
import org.dspace.handle.HandleManager;
import org.dspace.search.DSIndexer;

/**
 * MediaFilterManager is the class that invokes the media/format filters over the
 * repository's content. a few command line flags affect the operation of the
 * MFM: -v verbose outputs all extracted text to STDOUT; -f force forces all
 * bitstreams to be processed, even if they have been before; -n noindex does not
 * recreate index after processing bitstreams; -i [identifier] limits processing 
 * scope to a community, collection or item; and -m [max] limits processing to a
 * maximum number of items.
 */
public class MediaFilterManager
{
	//key (in dspace.cfg) which lists all enabled filters by name
    public static String MEDIA_FILTER_PLUGINS_KEY = "filter.plugins";
	
    //prefix (in dspace.cfg) for all filter properties
    public static String FILTER_PREFIX = "filter";
    
    //suffix (in dspace.cfg) for input formats supported by each filter
    public static String INPUT_FORMATS_SUFFIX = "inputFormats";
    
    public static boolean updateIndex = true; // default to updating index

    public static boolean isVerbose = false; // default to not verbose

    public static boolean isForce = false; // default to not forced
    
    public static String identifier = null; // object scope limiter
    
    public static int max2Process = Integer.MAX_VALUE;  // maximum number items to process
    
    public static int processed = 0;   // number items processed
    
    private static Item currentItem = null;   // current item being processed
    
    private static FormatFilter[] filterClasses = null;
    
    private static Map filterFormats = new HashMap();
    
    private static List skipList = null; //list of identifiers to skip during processing
    
    //separator in filterFormats Map between a filter class name and a plugin name,
    //for MediaFilters which extend SelfNamedPlugin (\034 is "file separator" char)
    public static String FILTER_PLUGIN_SEPARATOR = "\034";
    
    public static void main(String[] argv) throws Exception
    {
        // set headless for non-gui workstations
        System.setProperty("java.awt.headless", "true");

        // create an options object and populate it
        CommandLineParser parser = new PosixParser();

        int status = 0;

        Options options = new Options();
        
        options.addOption("v", "verbose", false,
                "print all extracted text and other details to STDOUT");
        options.addOption("f", "force", false,
                "force all bitstreams to be processed");
        options.addOption("n", "noindex", false,
                "do NOT update the search index after filtering bitstreams");
        options.addOption("i", "identifier", true,
        		"ONLY process bitstreams belonging to identifier");
        options.addOption("m", "maximum", true,
				"process no more than maximum items");
        options.addOption("h", "help", false, "help");

        //create a "plugin" option (to specify specific MediaFilter plugins to run)
        OptionBuilder.withLongOpt("plugins");
        OptionBuilder.withValueSeparator(',');
        OptionBuilder.withDescription(
                       "ONLY run the specified Media Filter plugin(s)\n" +
                       "listed from '" + MEDIA_FILTER_PLUGINS_KEY + "' in dspace.cfg.\n" + 
                       "Separate multiple with a comma (,)\n" +
                       "(e.g. MediaFilterManager -p \n\"Word Text Extractor\",\"PDF Text Extractor\")");                
        Option pluginOption = OptionBuilder.create('p');
        pluginOption.setArgs(Option.UNLIMITED_VALUES); //unlimited number of args
        options.addOption(pluginOption);	
        
         //create a "skip" option (to specify communities/collections/items to skip)
        OptionBuilder.withLongOpt("skip");
        OptionBuilder.withValueSeparator(',');
        OptionBuilder.withDescription(
                "SKIP the bitstreams belonging to identifier\n" + 
                "Separate multiple identifiers with a comma (,)\n" +
                "(e.g. MediaFilterManager -s \n 123456789/34,123456789/323)");                
        Option skipOption = OptionBuilder.create('s');
        skipOption.setArgs(Option.UNLIMITED_VALUES); //unlimited number of args
        options.addOption(skipOption);    
        
        CommandLine line = null;
        try
        {
            line = parser.parse(options, argv);
        }
        catch(MissingArgumentException e)
        {
            System.out.println("ERROR: " + e.getMessage());
            HelpFormatter myhelp = new HelpFormatter();
            myhelp.printHelp("MediaFilterManager\n", options);
            System.exit(1);
        }          

        if (line.hasOption('h'))
        {
            HelpFormatter myhelp = new HelpFormatter();
            myhelp.printHelp("MediaFilterManager\n", options);

            System.exit(0);
        }

        if (line.hasOption('v'))
        {
            isVerbose = true;
        }

        if (line.hasOption('n'))
        {
            updateIndex = false;
        }

        if (line.hasOption('f'))
        {
            isForce = true;
        }
        
        if (line.hasOption('i'))
        {
        	identifier = line.getOptionValue('i');
        }
        
        if (line.hasOption('m'))
        {
        	max2Process = Integer.parseInt(line.getOptionValue('m'));
        	if (max2Process <= 1)
        	{
        		System.out.println("Invalid maximum value '" + 
        				     		line.getOptionValue('m') + "' - ignoring");
        		max2Process = Integer.MAX_VALUE;
        	}
        }

        String filterNames[] = null;
        if(line.hasOption('p'))
        {
            //specified which media filter plugins we are using
            filterNames = line.getOptionValues('p');
        
            if(filterNames==null || filterNames.length==0)
            {   //display error, since no plugins specified
                System.err.println("\nERROR: -p (-plugin) option requires at least one plugin to be specified.\n" +
                                          "(e.g. MediaFilterManager -p \"Word Text Extractor\",\"PDF Text Extractor\")\n");
                HelpFormatter myhelp = new HelpFormatter();
                myhelp.printHelp("MediaFilterManager\n", options);
                System.exit(1);
             }
        }
        else
        { 
            //retrieve list of all enabled media filter plugins!
            String enabledPlugins = ConfigurationManager.getProperty(MEDIA_FILTER_PLUGINS_KEY);
            filterNames = enabledPlugins.split(",\\s*");
        }
                
        //initialize an array of our enabled filters
        List filterList = new ArrayList();
                
        //set up each filter
        for(int i=0; i< filterNames.length; i++)
        {
            //get filter of this name & add to list of filters
            FormatFilter filter = (FormatFilter) PluginManager.getNamedPlugin(FormatFilter.class, filterNames[i]);
            if(filter==null)
            {   
                System.err.println("\nERROR: Unknown MediaFilter specified (either from command-line or in dspace.cfg): '" + filterNames[i] + "'");
                System.exit(1);
            }
            else
            {   
                filterList.add(filter);
                       
                String filterClassName = filter.getClass().getName();
                           
                String pluginName = null;
                           
                //If this filter is a SelfNamedPlugin,
                //then the input formats it accepts may differ for
                //each "named" plugin that it defines.
                //So, we have to look for every key that fits the
                //following format: filter.<class-name>.<plugin-name>.inputFormats
                if( SelfNamedPlugin.class.isAssignableFrom(filter.getClass()) )
                {
                    //Get the plugin instance name for this class
                    pluginName = ((SelfNamedPlugin) filter).getPluginInstanceName();
                }
            
                
                //Retrieve our list of supported formats from dspace.cfg
                //For SelfNamedPlugins, format of key is:  
                //  filter.<class-name>.<plugin-name>.inputFormats
                //For other MediaFilters, format of key is: 
                //  filter.<class-name>.inputFormats
                String formats = ConfigurationManager.getProperty(
                    FILTER_PREFIX + "." + filterClassName + 
                    (pluginName!=null ? "." + pluginName : "") +
                    "." + INPUT_FORMATS_SUFFIX);
            
                //add to internal map of filters to supported formats	
                if (formats != null)
                {
                    //For SelfNamedPlugins, map key is:  
                    //  <class-name><separator><plugin-name>
                    //For other MediaFilters, map key is just:
                    //  <class-name>
                    filterFormats.put(filterClassName + 
        	            (pluginName!=null ? FILTER_PLUGIN_SEPARATOR + pluginName : ""),
        	            Arrays.asList(formats.split(",[\\s]*")));
                }
            }//end if filter!=null
        }//end for
        
        //If verbose, print out loaded mediafilter info
        if(isVerbose)
        {   
            System.out.println("The following MediaFilters are enabled: ");
            java.util.Iterator i = filterFormats.keySet().iterator();
            while(i.hasNext())
            {
                String filterName = (String) i.next();
                System.out.println("Full Filter Name: " + filterName);
                String pluginName = null;
                if(filterName.contains(FILTER_PLUGIN_SEPARATOR))
                {
                    String[] fields = filterName.split(FILTER_PLUGIN_SEPARATOR);
                    filterName=fields[0];
                    pluginName=fields[1];
                }
                 
                System.out.println(filterName +
                        (pluginName!=null? " (Plugin: " + pluginName + ")": ""));
             }
        }
              
        //store our filter list into an internal array
        filterClasses = (FormatFilter[]) filterList.toArray(new FormatFilter[filterList.size()]);
        
        
        //Retrieve list of identifiers to skip (if any)
        String skipIds[] = null;
        if(line.hasOption('s'))
        {
            //specified which identifiers to skip when processing
            skipIds = line.getOptionValues('s');
            
            if(skipIds==null || skipIds.length==0)
            {   //display error, since no identifiers specified to skip
                System.err.println("\nERROR: -s (-skip) option requires at least one identifier to SKIP.\n" +
                                    "Make sure to separate multiple identifiers with a comma!\n" +
                                    "(e.g. MediaFilterManager -s 123456789/34,123456789/323)\n");
                HelpFormatter myhelp = new HelpFormatter();
                myhelp.printHelp("MediaFilterManager\n", options);
                System.exit(0);
            }
            
            //save to a global skip list
            skipList = Arrays.asList(skipIds);
        }
        
        Context c = null;

        try
        {
            c = new Context();

            // have to be super-user to do the filtering
            c.setIgnoreAuthorization(true);

            // now apply the filters
            if (identifier == null)
            {
            	applyFiltersAllItems(c);
            }
            else  // restrict application scope to identifier
            {
            	DSpaceObject dso = HandleManager.resolveToObject(c, identifier);
            	if (dso == null)
            	{
            		throw new IllegalArgumentException("Cannot resolve "
                                + identifier + " to a DSpace object");
            	}
            	
            	switch (dso.getType())
            	{
            		case Constants.COMMUNITY:
            						applyFiltersCommunity(c, (Community)dso);
            						break;					
            		case Constants.COLLECTION:
            						applyFiltersCollection(c, (Collection)dso);
            						break;						
            		case Constants.ITEM:
            						applyFiltersItem(c, (Item)dso);
            						break;
            	}
            }
          
            // update search index?
            if (updateIndex)
            {
                System.out.println("Updating search index:");
                DSIndexer.updateIndex(c);
            }

            c.complete();
            c = null;
        }
        catch (Exception e)
        {
            status = 1;
        }
        finally
        {
            if (c != null)
            {
                c.abort();
            }
        }
        System.exit(status);
    }

    public static void applyFiltersAllItems(Context c) throws Exception
    {
        if(skipList!=null)
        {    
            //if a skip-list exists, we need to filter community-by-community
            //so we can respect what is in the skip-list
            Community[] topLevelCommunities = Community.findAllTop(c);
          
            for(int i=0; i<topLevelCommunities.length; i++)
                applyFiltersCommunity(c, topLevelCommunities[i]);
        }
        else 
        {
            //otherwise, just find every item and process
            ItemIterator i = Item.findAll(c);
            while (i.hasNext() && processed < max2Process)
            {
            	applyFiltersItem(c, i.next());
            }
        }
    }
    
    public static void applyFiltersCommunity(Context c, Community community)
                                             throws Exception
    {   //only apply filters if community not in skip-list
        if(!inSkipList(community.getHandle()))
        {    
           	Community[] subcommunities = community.getSubcommunities();
           	for (int i = 0; i < subcommunities.length; i++)
           	{
           		applyFiltersCommunity(c, subcommunities[i]);
           	}
           	
           	Collection[] collections = community.getCollections();
           	for (int j = 0; j < collections.length; j++)
           	{
           		applyFiltersCollection(c, collections[j]);
           	}
        }
    }
        
    public static void applyFiltersCollection(Context c, Collection collection)
                                              throws Exception
    {
        //only apply filters if collection not in skip-list
        if(!inSkipList(collection.getHandle()))
        {
            ItemIterator i = collection.getItems();
            while (i.hasNext() && processed < max2Process)
            {
            	applyFiltersItem(c, i.next());
            }
        }
    }
       
    public static void applyFiltersItem(Context c, Item item) throws Exception
    {
        //only apply filters if item not in skip-list
        if(!inSkipList(item.getHandle()))
        {
    	  //cache this item in MediaFilterManager
    	  //so it can be accessed by MediaFilters as necessary
    	  currentItem = item;
    	
          if (filterItem(c, item))
          {
        	  // commit changes after each filtered item
        	  c.commit();
              // increment processed count
              ++processed;
          }
          // clear item objects from context cache and internal cache
          item.decache();
          currentItem = null;
        }  
    }

    /**
     * iterate through the item's bitstreams in the ORIGINAL bundle, applying
     * filters if possible
     * 
     * @return true if any bitstreams processed, 
     *         false if none
     */
    public static boolean filterItem(Context c, Item myItem) throws Exception
    {
        // get 'original' bundles
        Bundle[] myBundles = myItem.getBundles("ORIGINAL");
        boolean done = false;
        for (int i = 0; i < myBundles.length; i++)
        {
        	// now look at all of the bitstreams
            Bitstream[] myBitstreams = myBundles[i].getBitstreams();
            
            for (int k = 0; k < myBitstreams.length; k++)
            {
            	done |= filterBitstream(c, myItem, myBitstreams[k]);
            }
        }
        return done;
    }

    /**
     * Attempt to filter a bitstream
     * 
     * An exception will be thrown if the media filter class cannot be
     * instantiated, exceptions from filtering will be logged to STDOUT and
     * swallowed.
     * 
     * @return true if bitstream processed, 
     *         false if no applicable filter or already processed
     */
    public static boolean filterBitstream(Context c, Item myItem,
            Bitstream myBitstream) throws Exception
    {
    	boolean filtered = false;
    	
    	// iterate through filter classes. A single format may be actioned
    	// by more than one filter
    	for (int i = 0; i < filterClasses.length; i++)
    	{
    		//List fmts = (List)filterFormats.get(filterClasses[i].getClass().getName());
    	    String pluginName = null;
    	               
    	    //if this filter class is a SelfNamedPlugin,
    	    //its list of supported formats is different for
    	    //differently named "plugin"
    	    if( SelfNamedPlugin.class.isAssignableFrom(filterClasses[i].getClass()) )
    	    {
    	        //get plugin instance name for this media filter
    	        pluginName = ((SelfNamedPlugin)filterClasses[i]).getPluginInstanceName();
    	    }
    	               
    	    //Get list of supported formats for the filter (and possibly named plugin)
    	    //For SelfNamedPlugins, map key is:  
    	    //  <class-name><separator><plugin-name>
    	    //For other MediaFilters, map key is just:
    	    //  <class-name>
    	    List fmts = (List)filterFormats.get(filterClasses[i].getClass().getName() + 
    	                       (pluginName!=null ? FILTER_PLUGIN_SEPARATOR + pluginName : ""));
    	   
    	    if (fmts.contains(myBitstream.getFormat().getShortDescription()))
    		{
            	try
            	{
		            // only update item if bitstream not skipped
		            if (processBitstream(c, myItem, myBitstream, filterClasses[i]))
            	    {
		           		myItem.update(); // Make sure new bitstream has a sequence
		                                 	// number
		           		filtered = true;
		            }
            	}
                catch (Exception e)
                {
                    System.out.println("ERROR filtering, skipping bitstream #"
                            + myBitstream.getID() + " " + e);
                    e.printStackTrace();
                }
    		}
    	}
        return filtered;
    }
    
    /**
     * processBitstream is a utility class that calls the virtual methods
     * from the current MediaFilter class.
     * It scans the bitstreams in an item, and decides if a bitstream has 
     * already been filtered, and if not or if overWrite is set, invokes the 
     * filter.
     * 
     * @param c
     *            context
     * @param item
     *            item containing bitstream to process
     * @param source
     *            source bitstream to process
     * @param formatFilter
     *            FormatFilter to perform filtering
     * 
     * @return true if new rendition is created, false if rendition already
     *         exists and overWrite is not set
     */
    public static boolean processBitstream(Context c, Item item, Bitstream source, FormatFilter formatFilter)
            throws Exception
    {
        //do pre-processing of this bitstream, and if it fails, skip this bitstream!
    	if(!formatFilter.preProcessBitstream(c, item, source))
        	return false;
        	
    	boolean overWrite = MediaFilterManager.isForce;
        
        // get bitstream filename, calculate destination filename
        String newName = formatFilter.getFilteredName(source.getName());

        Bitstream existingBitstream = null; // is there an existing rendition?
        Bundle targetBundle = null; // bundle we're modifying

        Bundle[] bundles = item.getBundles(formatFilter.getBundleName());

        // check if destination bitstream exists
        if (bundles.length > 0)
        {
            // only finds the last match (FIXME?)
            for (int i = 0; i < bundles.length; i++)
            {
                Bitstream[] bitstreams = bundles[i].getBitstreams();

                for (int j = 0; j < bitstreams.length; j++)
                {
                    if (bitstreams[j].getName().equals(newName))
                    {
                        targetBundle = bundles[i];
                        existingBitstream = bitstreams[j];
                    }
                }
            }
        }

        // if exists and overwrite = false, exit
        if (!overWrite && (existingBitstream != null))
        {
            System.out.println("SKIPPED: bitstream " + source.getID()
                    + " because '" + newName + "' already exists");

            return false;
        }

        InputStream destStream = formatFilter.getDestinationStream(source.retrieve());

        // create new bundle if needed
        if (bundles.length < 1)
        {
            targetBundle = item.createBundle(formatFilter.getBundleName());
        }
        else
        {
            // take the first match
            targetBundle = bundles[0];
        }

        Bitstream b = targetBundle.createBitstream(destStream);

        // Now set the format and name of the bitstream
        b.setName(newName);
        b.setSource("Written by FormatFilter " + formatFilter.getClass().getName() +
        			" on " + DCDate.getCurrent() + " (GMT)."); 
        b.setDescription(formatFilter.getDescription());

        // Find the proper format
        BitstreamFormat bf = BitstreamFormat.findByShortDescription(c,
                formatFilter.getFormatString());
        b.setFormat(bf);
        b.update();
        
        //Inherit policies from the source bitstream
        //(first remove any existing policies)
        AuthorizeManager.removeAllPolicies(c, b);
        AuthorizeManager.inheritPolicies(c, source, b);

        // fixme - set date?
        // we are overwriting, so remove old bitstream
        if (existingBitstream != null)
        {
            targetBundle.removeBitstream(existingBitstream);
        }

        System.out.println("FILTERED: bitstream " + source.getID()
                + " and created '" + newName + "'");

        //do post-processing of the generated bitstream
        formatFilter.postProcessBitstream(c, item, b);
        
        return true;
    }
    
    /**
     * Return the item that is currently being processed/filtered
     * by the MediaFilterManager
     * <p>
     * This allows FormatFilters to retrieve the Item object
     * in case they need access to item-level information for their format
     * transformations/conversions.
     * 
     * @return current Item being processed by MediaFilterManager
     */
    public static Item getCurrentItem()
    {
        return currentItem;
    }
    
    /**
     * Check whether or not to skip processing the given identifier
     * 
     * @param identifier
     *            identifier (handle) of a community, collection or item
     *            
     * @return true if this community, collection or item should be skipped
     *          during processing.  Otherwise, return false.
     */
    public static boolean inSkipList(String identifier)
    {
        if(skipList!=null && skipList.contains(identifier))
        {
            System.out.println("SKIP-LIST: skipped bitstreams within identifier " + identifier);
            return true;
        }    
        else
            return false;
    }
    
}