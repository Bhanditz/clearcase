/*
 * Copyright (c) 2000-2006 JetBrains s.r.o. All Rights Reserved.
 */

package net.sourceforge.transparent.ChangeManagement;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentIterator;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.LineTokenizer;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;
import net.sourceforge.transparent.*;
import static net.sourceforge.transparent.TransparentVcs.MERGE_CONFLICT;
import static net.sourceforge.transparent.TransparentVcs.SUCCESSFUL_CHECKOUT;
import net.sourceforge.transparent.exceptions.ClearCaseException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: lloix
 * Date: Dec 6, 2006
 */
public class CCaseChangeProvider implements ChangeProvider
{
  @NonNls private final static String REMINDER_TITLE = "Reminder";
  @NonNls private final static String REMINDER_TEXT = "Project started with ClearCase configured to be in the Offline mode.";

  @NonNls private final static String COLLECT_MSG = "Collecting writable files";
  @NonNls private final static String SEARCHNEW_MSG = "Searching New";
  @NonNls private final static String FAIL_2_CONNECT_MSG = "Failed to connect to ClearCase Server: ";
  @NonNls private final static String FAIL_2_CONNECT_TITLE = "Server Connection Problem";
  @NonNls private final static String FAIL_2_START = "Failed to start Cleartool. Check ClearCase installation.";

  /**
   * If amount of writable files during the batch call exceeds this number,
   * switch from iterative calls to cleartool's LS command to the different
   * scheme: call "cleartool findcheckouts" to find all "honestly" modified
   * files, others are considered NEW (since we will not analyze them for
   * "HIJACKED" status).
   */
  private static int MAX_FILES_FOR_ITERATIVE_STATUS = 200;

  private static final Logger LOG = Logger.getInstance("#net.sourceforge.transparent.ChangeManagement.CCaseChangeProvider");

  private Project project;
  private TransparentVcs host;
  private CCaseConfig config;
  private ProgressIndicator progress;
  private boolean isBatchUpdate;
  private boolean isFirstShow;

  private HashSet<String> filesWritable = new HashSet<String>();
  private HashSet<String> filesNew = new HashSet<String>();
  private HashSet<String> filesChanged = new HashSet<String>();
  private HashSet<String> filesHijacked = new HashSet<String>();
  private HashSet<String> filesIgnored = new HashSet<String>();
  private HashSet<String> filesMerge = new HashSet<String>();

  public CCaseChangeProvider( Project project, TransparentVcs hostVcs )
  {
    this.project = project;
    host = hostVcs;
    isFirstShow = true;
  }

  public boolean isModifiedDocumentTrackingRequired() { return false;  }

  public void getChanges( final VcsDirtyScope dirtyScope, final ChangelistBuilder builder,
                          final ProgressIndicator progressIndicator )
  {
    //-------------------------------------------------------------------------
    //  Protect ourselves from the calls which come during the unsafe project
    //  phases like unload or reload.
    //-------------------------------------------------------------------------
    if( project.isDisposed() )
      return;

    validateChangesOverTheHost( dirtyScope );
    logChangesContent( dirtyScope );

    //  Do not perform any actions if we have no VSS-related
    //  content roots configured.
    if( ProjectLevelVcsManager.getInstance( project ).getDirectoryMappings( host ).size() == 0 )
      return;
    
    config = host.getConfig();
    progress = progressIndicator;
    isBatchUpdate = isBatchUpdate( dirtyScope );

    showOptionalReminder();
    initInternals();
    isFirstShow = false;

    try
    {
      if( isBatchUpdate )
      {
        iterateOverProjectStructure( dirtyScope );
      }
      iterateOverDirtyDirectories( dirtyScope );
      iterateOverDirtyFiles( dirtyScope );
      LOG.info( "-- ChangeProvider - passed collection phase" );

      //  Perform status computation only if we operate in the online mode.
      //  For offline mode just display the last valid state (for new and
      //  modified files, others are hijacked).
      if( !config.isOffline )
      {
        computeStatuses();
      }
      else
      {
        restoreStatusesFromCached();
      }
      processStatusExceptions();

      LOG.info( "-- ChangeProvider - passed analysis phase" );
      
      //-----------------------------------------------------------------------
      //  For an UCM view we must determine the corresponding changes list name
      //  which is associated with the "activity" of the particular view.
      //-----------------------------------------------------------------------
      if( config.useUcmModel )
        setActivityInfoOnChangedFiles();

      LOG.info( "-- ChangeProvider - passed activity description phase" );

      addAddedFiles( builder );
      addChangedFiles( builder );
      addRemovedFiles( builder );
      addIgnoredFiles( builder );
      addMergeConflictFiles( builder );
    }
    catch( ClearCaseException e )
    {
      @NonNls String message = FAIL_2_CONNECT_MSG + e.getMessage();
      if( TransparentVcs.isServerDownMessage( e.getMessage() ))
      {
        message += "\n\nSwitching to the isOffline mode";
        host.getConfig().isOffline = true;
      }
      final String msg = message;
      ApplicationManager.getApplication().invokeLater( new Runnable() { public void run() { VcsUtil.showErrorMessage( project, msg, FAIL_2_CONNECT_TITLE ); } });
      LOG.info( message );
    }
    catch( RuntimeException e )
    {
      @NonNls final String message = FAIL_2_START + ": " + e.getMessage();
      ApplicationManager.getApplication().invokeLater( new Runnable() { public void run() { VcsUtil.showErrorMessage( project, message, FAIL_2_CONNECT_TITLE ); } });
      LOG.info( message );
    }
    finally
    {
      TransparentVcs.LOG.info( "-- EndChangeProvider| New: " + filesNew.size() + ", modified: " + filesChanged.size() +
                               ", hijacked:" + filesHijacked.size() + ", ignored: " + filesIgnored.size() );
    }
  }

  /**
   * When we start for the very first time - show reminder that user possibly
   * forgot that last time he set option to "Work offline".
   */
  private void showOptionalReminder()
  {
    if( isBatchUpdate && isFirstShow && config.isOffline )
    {
      ApplicationManager.getApplication().invokeLater( new Runnable() {
         public void run() {  Messages.showWarningDialog( project, REMINDER_TEXT, REMINDER_TITLE );  }
       });
    }
  }

  private void collectCheckouts( HashSet<String> files )
  {
    LOG.info( "---ChangeProvider - Checking status by analyzing the set of checked out files via LSCO.");

    VirtualFile[] roots = ProjectLevelVcsManager.getInstance( project ).getRootsUnderVcs( host );
    for( VirtualFile root : roots )
    {
      String out = TransparentVcs.cleartoolOnLocalPathWithOutput( root.getPath(), "lsco", "-me", "-short", "-recurse" );
      String[] lines = LineTokenizer.tokenize( out, false );
      for( String line : lines )
      {
        String fileName = root.getPath() + "/" + VcsUtil.getCanonicalLocalPath( line );
        File file = new File( fileName );
        if( file.exists() && !file.isDirectory() )
        {
          try
          {
            String path = VcsUtil.getCanonicalLocalPath( file.getCanonicalPath() ).toLowerCase();
            files.add( path );
          }
          catch( IOException e ){
            //  Nothing to do - we have no idea on what shit cleartool decided to return.
          }
        }
      }
    }
    LOG.info( "---ChangeProvider - Total " + files.size() + " non-folder checkouts found by LSCO.");
  }

  /**
   *  Iterate over the project structure, find all writable files in the project,
   *  and check their status against the VSS repository. If file exists in the repository
   *  it is assigned "changed" status, otherwise it has "new" status.
   */
  private void iterateOverProjectStructure( final VcsDirtyScope dirtyScope )
  {
    for( FilePath path : dirtyScope.getRecursivelyDirtyDirectories() )
    {
      LOG.info( "-- ChangeProvider - Iterating over content root: " + path.getPath() );
      if( progress != null )
        progress.setText( COLLECT_MSG );

      collectWritableFiles( path );

      LOG.info( "-- ChangeProvider - Total: " + filesWritable.size() + " writable files after the last view." );
      if( progress != null )
        progress.setText( SEARCHNEW_MSG );
    }
  }

  /**
   *  Deleted and New folders are marked as dirty too and we provide here
   *  special processing for them.
   */
  private void iterateOverDirtyDirectories( final VcsDirtyScope dirtyScope )
  {
    for( FilePath path : dirtyScope.getDirtyFiles() )
    {
      String fileName = path.getPath();
      VirtualFile file = path.getVirtualFile();

      //  make sure that:
      //  - a file is a folder which exists physically
      //  - it is under out vcs and is not in the ignore list
      if( path.isDirectory() && (file != null) && host.fileIsUnderVcs( path ) )
      {
        if( host.isFileIgnored( file ))
          filesIgnored.add( fileName );
        else
        {
          String refName = discoverOldName( fileName );

          //  Check that folder physically exists.
          if( !host.fileExistsInVcs( refName ))
            filesNew.add( fileName );
          else
          //  NB: Do not put to the "Changed" list those folders which are under
          //      the renamed one since we will have troubles in checking such
          //      folders in (it is useless, BTW).
          //      Simultaneously, this prevents valid processing of renamed folders
          //      that are under another renamed folders.
          //  Todo Inner rename.
          if( !refName.equals( fileName ) && !isUnderRenamedFolder( fileName ) )
            filesChanged.add( fileName );
        }
      }
    }
  }

  private void iterateOverDirtyFiles( final VcsDirtyScope scope )
  {
    for( FilePath path : scope.getDirtyFiles() )
    {
      String fileName = path.getPath();
      VirtualFile file = path.getVirtualFile();

      if( host.isFileIgnored( file ))
        filesIgnored.add( fileName );
      else
      if( isFileCCaseProcessable( file ) && isProperNotification( path ) )
        filesWritable.add( fileName );
    }
  }

  /**
   * Iterate over the project structure and collect two types of files:
   * - writable files, they are the subject for subsequent analysis
   * - "ignored" files - which will be shown in a separate changes folder.
   */
  private void collectWritableFiles( final FilePath filePath )
  {
    final ProjectFileIndex fileIndex = ProjectRootManager.getInstance( project ).getFileIndex();
    VirtualFile vf = filePath.getVirtualFile();
    if( vf != null )
    {
      fileIndex.iterateContentUnderDirectory( vf, new ContentIterator()
        {
          public boolean processFile( VirtualFile file )
          {
            String path = file.getPath();
            if( VcsUtil.isPathUnderProject( project, path ) && isValidFile( file ) )
            {
              if( host.isFileIgnored( file ) )
                filesIgnored.add( path );
              else
                filesWritable.add( path );
            }
            return true;
          }
        } );
    }
  }

  private void computeStatuses()
  {
    LOG.info( "---ChangeProvider - " + filesIgnored.size() + " ignored files accumulated so far.");

    if( filesWritable.size() < MAX_FILES_FOR_ITERATIVE_STATUS )
    {
      analyzeWritableFiles( filesWritable );
    }
    else
    {
      collectCheckouts( filesChanged );
      for( String file : filesWritable )
      {
        String normPath = VcsUtil.getCanonicalLocalPath( file ).toLowerCase();
        if( !filesChanged.contains( normPath ) )
          filesNew.add( file );
      }
    }
  }

  private void analyzeWritableFiles( HashSet<String> writables )
  {
    if( writables.size() == 0 )
      return;

    //-------------------------------------------------------------------------
    //  1. Exclude those files for which status is known apriori:
    //    - file has status "changed" right after it was checked out
    //    - file has status "Merge Conflict" if that was indicated during
    //      the last commit operation.
    //  2. Guess file status given its previous file status.
    //-------------------------------------------------------------------------
    List<String> writableFiles = filterOutMarkedFiles( writables );
    writableFiles = filterOutGuessedFiles( writableFiles );

    //-------------------------------------------------------------------------
    List<String> refNames = new ArrayList<String>();
    for( String file : writableFiles )
    {
      String legalName = discoverOldName( file );
      refNames.add( legalName );
    }

    LOG.info( "ChangeProvider - Analyzing writables in batch mode using CLEARTOOL on " + writables.size() + " files." );

    final List<String> newFiles = new ArrayList<String>();
    StatusMultipleProcessor processor = new StatusMultipleProcessor( refNames );
    processor.execute();
    LOG.info( "ChangeProvider - \"CLEARTOOL LS\" batch command finished." );

    for( int i = 0; i < writableFiles.size(); i++ )
    {
      if( processor.isNonexist( refNames.get( i ) ))
        newFiles.add( writableFiles.get( i ) );
      else
      if( processor.isCheckedout( refNames.get( i ) ))
        filesChanged.add( writableFiles.get( i ) );
      else
      if( processor.isHijacked( refNames.get( i ) ))
        filesHijacked.add( writableFiles.get( i ) );
    }

    if( isBatchUpdate )
    {
      //  For each new file check whether parent folders structure is also new.
      //  If so - mark these folders as dirty and assign them new statuses on the
      //  next iteration to "getChanges()".
      final List<String> newFolders = new ArrayList<String>();
      final HashSet<String> processedFolders = new HashSet<String>();
      newFiles.addAll( filesNew ); //  in order to analyze all of them.
      for( String file : newFiles )
      {
        if( !isPathUnderProcessedFolders( processedFolders, file ))
          analyzeParentFoldersForPresence( file, newFolders, processedFolders );
      }

      filesNew.addAll( newFolders );
    }
    filesNew.addAll( newFiles );
  }

  /**
   * Do not analyze the file if we know that this file just has been
   * successfully checked out from the repository, its RO status is
   * writable and it is ready for editing.
   */
  private List<String> filterOutMarkedFiles( HashSet<String> list )
  {
    ArrayList<String> files = new ArrayList<String>();
    for( String path : list )
    {
      VirtualFile file = VcsUtil.getVirtualFile( path );

      if( file.getUserData( SUCCESSFUL_CHECKOUT ) != null  )
      {
        //  Do not forget to delete this property right after the change
        //  is classified, otherwise this file will always be determined
        //  as modified.
        file.putUserData( SUCCESSFUL_CHECKOUT, null );
        filesChanged.add( file.getPath() );
      }
      else
      if( file.getUserData( MERGE_CONFLICT ) != null )
      {
        filesMerge.add( file.getPath() );
      }
      else
      {
        files.add( path );
      }
    }
    return files;
  }

  /**
   * Trying to guess the new file status out of its previous one. During
   * these simple steps use the following heuristics:
   * - if the file has previously the status "MODIFIED: since it passed the
   *   "isProperNotification" predicate then it is not a deleted file; thus
   *   the only possible current status is again "MODIFIED".
   * - if the file has previously the status "ADDED" or "UNVERSIONED": it can
   *   keep the same status, or follow "ADDED"->"UNVERTIONED" or
   *   "UNVERSIONED"->"ADDED" pathway; since we do not support "keep checked out
   *   after checkin" then it cannot get status "MODIFIED". Since the distinction
   *   between "ADDED" or "UNVERSIONED" is done later, we can save this file in
   *   "new files" list.
   */
  private List<String> filterOutGuessedFiles( List<String> list )
  {
    ArrayList<String> files = new ArrayList<String>();
    for( String file : list )
    {
      boolean guessed = false;
      VirtualFile vfile = VcsUtil.getVirtualFile( file );
      if( vfile != null )
      {
        FileStatus status = FileStatusManager.getInstance( project).getStatus( vfile );
        /*
        if( status == FileStatus.MODIFIED )
        {
          filesChanged.add( file );
          guessed = true;
        }
        else
        */
        if( status == FileStatus.ADDED || status == FileStatus.UNKNOWN )
        {
          filesNew.add( file );
          guessed = true;
        }
      }
      
      if( !guessed )
      {
        files.add( file );
      }
    }
    return files;
  }

  /**
   * Process exceptions of different kind when normal computation of file
   * statuses is cheated by the IDEA:
   * 1. "Extract Superclass" refactoring with "Rename original class" option set.
   *    Refactoring renamed the original class (right) but writes new content to
   *    the file with the olf name (fuck!).
   *    Remedy: Find such file in the list of "Changed" files, check whether its
   *            name is in the list of New files (from VFSListener), and check
   *            whether its name is in the record for renamed files, then move
   *            it into "New" files list.
   */
  private void processStatusExceptions()
  {
    // 1.
    for( Iterator<String> it = filesChanged.iterator(); it.hasNext(); )
    {
      String fileName = it.next();
      if( host.isNewOverRenamed( fileName ) )
      {
        it.remove();
        filesNew.add( fileName );
      }
    }
  }

  /**
   * For a given file which is known to be new, check also its direct parent
   * folder for presence in the VSS repository, and then all its indirect parent
   * folders until we reach project boundaries or find the existing folder.
  */
  private void analyzeParentFoldersForPresence( String file, List<String> newFolders,
                                                HashSet<String> processed )
  {
    String fileParent = new File( file ).getParentFile().getPath();
    String fileParentNorm = VcsUtil.getCanonicalLocalPath( fileParent );
    String refParentName = discoverOldName( fileParentNorm );

    if( host.fileIsUnderVcs( fileParent ) && !processed.contains( fileParent.toLowerCase() ) )
    {
      LOG.info( "ChangeProvider - Check potentially new folder" );
      
      processed.add( fileParent.toLowerCase() );
      if( !host.fileExistsInVcs( refParentName ))
      {
        LOG.info( "                 Folder [" + fileParent + "] is not in the repository" );
        newFolders.add( fileParent );
        analyzeParentFoldersForPresence( fileParent, newFolders, processed );
      }
    }
  }

  private void restoreStatusesFromCached()
  {
    for( String fileName : filesWritable )
    {
      if( host.containsModified( fileName ))
      {
        filesChanged.add( fileName );
      }
      else
      if( host.containsNew( fileName ) )
      {
        filesNew.add( fileName );
      }
      else
      {
        filesHijacked.add( fileName );
      }
    }
  }

  /**
   * File is either:
   * - "new" - it is not contained in the repository, but host contains
   *           a record about it (that is, it was manually moved to the
   *           list of files to be added to the commit.
   * - "unversioned" - it is not contained in the repository yet.
   */
  private void addAddedFiles( final ChangelistBuilder builder )
  {
    for( String fileName : filesNew )
    {
      //  In the case of file rename or parent folder rename we should
      //  refer to the list of new files by the
      String refName = discoverOldName( fileName );

      //  New file could be added AFTER and BEFORE e.g. the package rename.
      if( host.containsNew( fileName ) || host.containsNew( refName ))
      {
        FilePath path = VcsUtil.getFilePath( fileName );
        String activity = findActivityForFile( path, path );
        builder.processChangeInList( new Change( null, new CurrentContentRevision( path ) ), activity );
      }
      else
      {
        builder.processUnversionedFile( VcsUtil.getVirtualFile( fileName ) );
      }
    }
  }

  /**
   * For each changed file which has no known checkout activity find it
   * by processing "describe" command.
   */
  private void setActivityInfoOnChangedFiles()
  {
    List<String> filesToCheck = new ArrayList<String>();
    for( String fileName : filesChanged )
    {
      if( host.getCheckoutActivityForFile( fileName ) == null )
        filesToCheck.add( fileName );
    }

    setActivityInfoOnChangedFiles( filesToCheck );
  }

  /**
   * For each file in list find its activity by processing "describe" command.
   * @param files
   */
  public void setActivityInfoOnChangedFiles( final List<String> files )
  {
    List<String> refFilesToCheck = new ArrayList<String>();
    for( String fileName : files )
    {
      refFilesToCheck.add( discoverOldName( fileName ) );
    }

    boolean hasAlreadyReloadedActivities = false;
    DescribeMultipleProcessor processor = new DescribeMultipleProcessor( refFilesToCheck );
    processor.execute();

    for( int i = 0; i < refFilesToCheck.size(); i++ )
    {
      String activity = processor.getActivity( refFilesToCheck.get( i ) );
      if( activity != null )
      {
        String activityName = host.getNormalizedActivityName( activity );
        if( activityName == null )
        {
          //  Something has changed outside the IDEA, we need to synchronize
          //  views and activities all together to properly move the change
          //  into the changelist.
          if( !hasAlreadyReloadedActivities )
          {
            hasAlreadyReloadedActivities = true;
            host.extractViewActivities();
          }

          activityName = host.getNormalizedActivityName( activity );
        }

        if( activityName != null )
          host.addFile2Changelist( new File( files.get( i ) ), activityName );
      }
    }
  }

  /**
   * Add all files which were determined to be changed (somehow - modified,
   * renamed, etc) and folders which were renamed.
   * NB: adding folders information actually works only in either batch refresh
   *     of statuses or when some folder appears in the list of changes.
   */
  private void addChangedFiles( final ChangelistBuilder builder )
  {
    for( String fileName : filesChanged )
    {
      String validRefName = discoverOldName( fileName );
      add2ChangeList( builder, FileStatus.MODIFIED, fileName, validRefName );
    }

    for( String fileName : filesHijacked )
    {
      String validRefName = discoverOldName( fileName );
      add2ChangeList( builder, FileStatus.HIJACKED, fileName, validRefName );
    }

    for( String folderName : host.renamedFolders.keySet() )
    {
      String oldFolderName = host.renamedFolders.get( folderName );

//      add2ChangeList( builder, FileStatus.MODIFIED, folderName, oldFolderName );
//      final FilePath refPath = VcsUtil.getFilePath( oldFolderName );
      final FilePath refPath = VcsUtil.getFilePathForDeletedFile( oldFolderName, true );
      final FilePath currPath = VcsUtil.getFilePath( folderName ); // == refPath if no rename occured
      String activity = findActivityForFile( refPath, currPath );

      CCaseContentRevision revision = ContentRevisionFactory.getRevision( refPath, project );
      builder.processChangeInList( new Change( revision, new CurrentContentRevision( currPath ), FileStatus.MODIFIED ), activity );
    }
  }

  private void add2ChangeList( final ChangelistBuilder builder, FileStatus status,
                               String fileName, String validRefName )
  {
    final FilePath refPath = VcsUtil.getFilePath( validRefName );
    final FilePath currPath = VcsUtil.getFilePath( fileName ); // == refPath if no rename occured
    String activity = findActivityForFile( refPath, currPath );

    CCaseContentRevision revision = ContentRevisionFactory.getRevision( refPath, project );
    builder.processChangeInList( new Change( revision, new CurrentContentRevision( currPath ), status ), activity );
  }

  private void addRemovedFiles( final ChangelistBuilder builder )
  {
    //  Use additional set to remove async modification conflicts
    final HashSet<String> files = new HashSet<String>();
    files.addAll( host.removedFolders );
    for( String path : files )
      builder.processLocallyDeletedFile( VcsUtil.getFilePathForDeletedFile( path, true ) );

    files.clear();
    files.addAll( host.removedFiles );
    for( String path : files )
      builder.processLocallyDeletedFile( VcsUtil.getFilePathForDeletedFile( path, false ) );

    files.clear();
    files.addAll( host.deletedFolders );
    for( String path : files )
      builder.processChange( new Change( new CurrentContentRevision( VcsUtil.getFilePathForDeletedFile( path, true )), null, FileStatus.DELETED ));

    files.clear();
    files.addAll( host.deletedFiles );
    for( String path : files )
    {
      FilePath refPath = VcsUtil.getFilePathForDeletedFile( path, false );
      CCaseContentRevision revision = ContentRevisionFactory.getRevision( refPath, project );
      builder.processChange( new Change( revision, null, FileStatus.DELETED ));
    }
  }

  private void addIgnoredFiles( final ChangelistBuilder builder )
  {
    for( String path : filesIgnored )
      builder.processIgnoredFile( VcsUtil.getVirtualFile( path ) );
  }

  private void addMergeConflictFiles( final ChangelistBuilder builder )
  {
    for( String path : filesMerge )
    {
      final FilePath fp = VcsUtil.getFilePath( path );
      CCaseContentRevision revision = ContentRevisionFactory.getRevision( fp, project );
      builder.processChange( new Change( revision, new CurrentContentRevision( fp ), FileStatus.MERGED_WITH_CONFLICTS ));
    }
  }

  private static boolean isPathUnderProcessedFolders( HashSet<String> folders, String path )
  {
    String parentPathToCheck = new File( path ).getParent().toLowerCase();
    for( String folderPath : folders )
    {
      if( parentPathToCheck == folderPath )
        return true;
    }
    return false;
  }

  /**
   * For the renamed or moved file we receive two change requests: one for
   * the old file and one for the new one. For renamed file old request differs
   * in filename, for the moved one - in parent path name. This request must be
   * ignored since all preliminary information is already accumulated.
   */
  private static boolean isProperNotification( final FilePath filePath )
  {
    String oldName = filePath.getName();
    String newName = (filePath.getVirtualFile() == null) ? "" : filePath.getVirtualFile().getName();
    String oldParent = (filePath.getVirtualFileParent() == null) ? "" : filePath.getVirtualFileParent().getPath();
    String newParent = filePath.getPath().substring( 0, filePath.getPath().length() - oldName.length() - 1 );

    //  Check the case when the file is deleted - its FilePath's VirtualFile
    //  component is null and thus new name is empty.
    return newParent.equals( oldParent ) &&
          ( newName.equals( oldName ) || (newName == "" && oldName != "") );
  }

  private void initInternals()
  {
    filesWritable.clear();
    filesNew.clear();
    filesChanged.clear();
    filesHijacked.clear();
    filesIgnored.clear();
    filesMerge.clear();
  }

  /**
   * Request for changes is "batch" if all folders from
   * VcsDirtyScope.getRecursivelyDirtyDirectories() are content roots. 
   */
  private boolean isBatchUpdate( VcsDirtyScope scope )
  {
    boolean isBatch = false;
    ProjectLevelVcsManager mgr = ProjectLevelVcsManager.getInstance( project );
    VirtualFile[] roots = mgr.getRootsUnderVcs( host );
    for( FilePath path : scope.getRecursivelyDirtyDirectories() )
    {
      for( VirtualFile root : roots )
      {
        VirtualFile vfScopePath = path.getVirtualFile();
        //  VFile may be null in the case of deleted folders (IDEADEV-18855)
        isBatch = isBatch || (vfScopePath != null &&
                              vfScopePath.getPath().equalsIgnoreCase( root.getPath() ) );
      }
    }
    return isBatch;
  }
  
  private String discoverOldName( String file )
  {
    String canonicName = VcsUtil.getCanonicalLocalPath( file );
    String oldName = host.renamedFiles.get( canonicName );
    if( oldName == null )
    {
      oldName = host.renamedFolders.get( canonicName );
      if( oldName == null )
      {
        oldName = findInRenamedParentFolder( file );
        if( oldName == null )
          oldName = file;
        else
        {
          //  Idiosynchrasic check - whether a RENAMED file is found under the
          //  renamed folder?
          String checkRenamed = host.renamedFiles.get( oldName );
          if( checkRenamed != null )
            oldName = checkRenamed;
        }
      }
    }

    return oldName;
  }

  private String findInRenamedParentFolder( String name )
  {
    String fileInOldFolder = name;
    for( String folder : host.renamedFolders.keySet() )
    {
      String oldFolderName = host.renamedFolders.get( folder );
      if( name.startsWith( folder ) )
      {
        fileInOldFolder = oldFolderName + name.substring( folder.length() );
        break;
      }
    }
    return fileInOldFolder;
  }

  private boolean isUnderRenamedFolder( String fileName )
  {
    for( String folder : host.renamedFolders.keySet() )
    {
      if( fileName.startsWith( folder ) )
        return true;
    }
    return false;
  }

  private boolean isFileCCaseProcessable( VirtualFile file )
  {
    return isValidFile( file ) && VcsUtil.isPathUnderProject( project, file.getPath() );
  }

  private static boolean isValidFile( VirtualFile file )
  {
    return (file != null) && file.isWritable() && !file.isDirectory();
  }

  @Nullable
  private String findActivityForFile( FilePath refPath, final FilePath currPath )
  {
    String activity = null;

    //  Computing the activity name (to be used as the Changelist name) is defined
    //  only if the "UCM" mode is checked on. Otherwise IDEA's changelist preserve
    //  only their local semantics.
    if( config.useUcmModel )
    {
      //  First check whether the file was checked out under IDEA, we've
      //  parsed the "co" output and extracted the name of the activity under
      //  which the file is checked out.
      activity = host.getCheckoutActivityForFile( refPath.getPath() );
      if( activity == null )
      {
        //  Check the changelists which contain this particular file -
        //  if there is no such, then this file (change) is processed for the
        //  very first time and we need to find (or create) the appropriate
        //  change list for it.
        ChangeListManager mgr = ChangeListManager.getInstance( project );
        Change change = mgr.getChange( currPath );
        if( change == null )
        {
          //  1. Find the view responsible for this file.
          //  2. Take it current activity
          //  3. Find or create a change named after this activity.
          //  4. Remember that this file was first changed in this activity.

          VirtualFile root = VcsUtil.getVcsRootFor( project, currPath );
          TransparentVcs.ViewInfo info = host.viewsMap.get( root.getPath() );
          activity = info.activityName;

          if( activity == null )
            throw new NullPointerException( "Illegal (NULL) activity name from ViewInfo for a view " + info.tag );
          host.addFile2Changelist( refPath.getIOFile(), activity );
        }
      }
    }

    return activity;
  }

  private void validateChangesOverTheHost( final VcsDirtyScope scope )
  {
    ApplicationManager.getApplication().runReadAction( new Runnable() {
      public void run() {
        //  protect over being called in the forbidden phase
        if( !project.isDisposed() )
        {
          HashSet<FilePath> set = new HashSet<FilePath>();
          set.addAll( scope.getDirtyFiles() );
          set.addAll( scope.getRecursivelyDirtyDirectories() );

          ProjectLevelVcsManager mgr = ProjectLevelVcsManager.getInstance( project );
          for( FilePath path : set )
          {
            AbstractVcs fileHost = mgr.getVcsFor( path );
            LOG.assertTrue( fileHost == host, "Not valid scope for current Vcs: " + path.getPath() );
          }
        }
      }
    });
  }

  private static void logChangesContent( final VcsDirtyScope scope )
  {
    LOG.info( "-- ChangeProvider: Dirty files: " + scope.getDirtyFiles().size() +
              " == " + extMasks( scope.getDirtyFiles() ) +
              ",\n\t\t\t\t\tdirty recursive directories: " + scope.getRecursivelyDirtyDirectories().size() );
    for( FilePath path : scope.getDirtyFiles() )
      LOG.info( "                                " + path.getPath() );
    LOG.info( "                                ---" );
    for( FilePath path : scope.getRecursivelyDirtyDirectories() )
      LOG.info( "                                " + path.getPath() );
  }

  private static String extMasks( Set<FilePath> scope )
  {
    HashMap<String, Integer> masks = new HashMap<String, Integer>();
    for( FilePath path : scope )
    {
      int index = path.getName().lastIndexOf( '.' );
      if( index != -1 )
      {
        String ext = path.getName().substring( index );
        Integer count = masks.get( ext );
        masks.put( ext, (count == null) ? 1 : (count.intValue() + 1 ) );
      }
    }

    String masksStr = "";
    for( String ext : masks.keySet() )
    {
      masksStr += ext + " - " + masks.get( ext ).intValue() + "; ";
    }
    return masksStr;
  }
}
