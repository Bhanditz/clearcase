package net.sourceforge.transparent.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NonNls;

import java.util.ArrayList;
import java.util.List;

public class UndoCheckOutAction extends SynchronousAction
{
  @NonNls private final static String ACTION_NAME = "Undo Checkout";

  protected boolean isEnabled( VirtualFile file, AnActionEvent e )
  {
    return file.isDirectory() ||
           getFileStatus( getProject( e ), file ) == FileStatus.MODIFIED;
  }

  protected void perform( VirtualFile file, AnActionEvent e ) throws VcsException
  {
    List<VcsException> errors = new ArrayList<VcsException>();
    FileStatus status = getFileStatus( getProject( e ), file );
    
    if( !file.isDirectory() && (status == FileStatus.MODIFIED))
    {
      getHost( e ).undoCheckoutFile( file, errors );
      file.refresh( true, false );
    }

    if( errors.size() > 0 )
      throw errors.get( 0 );
  }

  protected String getActionName( AnActionEvent e ) { return ACTION_NAME; }
}
