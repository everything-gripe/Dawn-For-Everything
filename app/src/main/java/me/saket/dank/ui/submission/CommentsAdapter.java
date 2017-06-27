package me.saket.dank.ui.submission;

import android.support.annotation.CheckResult;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.RecyclerView;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import com.google.auto.value.AutoValue;
import com.jakewharton.rxrelay2.BehaviorRelay;

import net.dean.jraw.models.Comment;
import net.dean.jraw.models.CommentNode;
import net.dean.jraw.models.Flair;
import net.dean.jraw.models.VoteDirection;

import butterknife.BindColor;
import butterknife.BindString;
import butterknife.BindView;
import butterknife.ButterKnife;
import io.reactivex.Observable;
import me.saket.bettermovementmethod.BetterLinkMovementMethod;
import me.saket.dank.R;
import me.saket.dank.data.VotingManager;
import me.saket.dank.utils.Commons;
import me.saket.dank.utils.Dates;
import me.saket.dank.utils.JrawUtils;
import me.saket.dank.utils.Markdown;
import me.saket.dank.utils.RecyclerViewArrayAdapter;
import me.saket.dank.utils.Strings;
import me.saket.dank.utils.Truss;
import me.saket.dank.utils.Views;
import me.saket.dank.widgets.IndentedLayout;
import me.saket.dank.widgets.swipe.SwipeableLayout;
import me.saket.dank.widgets.swipe.ViewHolderWithSwipeActions;

public class CommentsAdapter extends RecyclerViewArrayAdapter<SubmissionCommentRow, RecyclerView.ViewHolder> {

  private static final int VIEW_TYPE_USER_COMMENT = 100;
  private static final int VIEW_TYPE_LOAD_MORE = 101;
  private static final int VIEW_TYPE_REPLY = 102;

  private final BetterLinkMovementMethod linkMovementMethod;
  private final VotingManager votingManager;
  private final CommentSwipeActionsProvider swipeActionsProvider;
  private final BehaviorRelay<CommentClickEvent> commentClickStream = BehaviorRelay.create();
  private final BehaviorRelay<LoadMoreCommentsClickEvent> loadMoreCommentsClickStream = BehaviorRelay.create();
  private String submissionAuthor;
  private ReplyActionsListener replyActionsListener;

  public interface ReplyActionsListener {
    void onClickDiscardReply(CommentNode nodeBeingRepliedTo);

    void onClickEditReplyInFullscreenMode(CommentNode nodeBeingRepliedTo);

    void onClickSendReply(CommentNode nodeBeingRepliedTo, String replyMessage);
  }

  @AutoValue
  abstract static class CommentClickEvent {
    public abstract CommentNode commentNode();

    public abstract View commentItemView();

    /**
     * Whether the comment was clicked to collapse.
     */
    public abstract boolean isCollapsing();

    public static CommentClickEvent create(CommentNode commentNode, View commentItemView, boolean isCollapsing) {
      return new AutoValue_CommentsAdapter_CommentClickEvent(commentNode, commentItemView, isCollapsing);
    }
  }

  @AutoValue
  abstract static class LoadMoreCommentsClickEvent {
    /**
     * Node whose more comments have to be fetched.
     */
    abstract CommentNode parentCommentNode();

    /**
     * Clicked itemView.
     */
    abstract View loadMoreItemView();

    public static LoadMoreCommentsClickEvent create(CommentNode parentNode, View loadMoreItemView) {
      return new AutoValue_CommentsAdapter_LoadMoreCommentsClickEvent(parentNode, loadMoreItemView);
    }
  }

  public CommentsAdapter(BetterLinkMovementMethod commentsLinkMovementMethod, VotingManager votingManager,
      CommentSwipeActionsProvider swipeActionsProvider)
  {
    this.linkMovementMethod = commentsLinkMovementMethod;
    this.votingManager = votingManager;
    this.swipeActionsProvider = swipeActionsProvider;
    setHasStableIds(true);
  }

  @CheckResult
  public Observable<CommentClickEvent> streamCommentClicks() {
    return commentClickStream;
  }

  @CheckResult
  public Observable<LoadMoreCommentsClickEvent> streamLoadMoreCommentsClicks() {
    return loadMoreCommentsClickStream;
  }

  /**
   * OP of a submission, highlighted in comments. This is being set manually instead of {@link Comment#getSubmissionAuthor()},
   * because that's always null. Not sure where I'm going wrong.
   */
  public void updateSubmissionAuthor(String submissionAuthor) {
    this.submissionAuthor = submissionAuthor;
  }

  public void setReplyActionsListener(ReplyActionsListener listener) {
    replyActionsListener = listener;
  }

  @Override
  public int getItemViewType(int position) {
    SubmissionCommentRow commentItem = getItem(position);
    switch (commentItem.type()) {
      case USER_COMMENT:
        return VIEW_TYPE_USER_COMMENT;

      case LOAD_MORE_COMMENTS:
        return VIEW_TYPE_LOAD_MORE;

      case REPLY:
        return VIEW_TYPE_REPLY;

      default:
        throw new UnsupportedOperationException();
    }
  }

  @Override
  protected RecyclerView.ViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent, int viewType) {
    switch (viewType) {
      case VIEW_TYPE_USER_COMMENT:
        UserCommentViewHolder holder = UserCommentViewHolder.create(inflater, parent, linkMovementMethod);
        holder.getSwipeableLayout().setSwipeActionIconProvider(swipeActionsProvider);
        return holder;

      case VIEW_TYPE_REPLY:
        return ReplyViewHolder.create(inflater, parent);

      case VIEW_TYPE_LOAD_MORE:
        return LoadMoreCommentViewHolder.create(inflater, parent);

      default:
        throw new UnsupportedOperationException();
    }
  }

  @Override
  public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
    SubmissionCommentRow commentItem = getItem(position);

    switch (commentItem.type()) {
      case USER_COMMENT:
        DankCommentNode dankCommentNode = (DankCommentNode) commentItem;
        CommentNode commentNode = dankCommentNode.commentNode();
        Comment comment = commentNode.getComment();

        VoteDirection pendingOrDefaultVoteDirection = votingManager.getPendingOrDefaultVote(comment, comment.getVote());
        int commentScore = votingManager.getScoreAfterAdjustingPendingVote(comment);
        boolean isAuthorOP = commentNode.getComment().getAuthor().equalsIgnoreCase(submissionAuthor);

        UserCommentViewHolder commentViewHolder = (UserCommentViewHolder) holder;
        commentViewHolder.bind(dankCommentNode, pendingOrDefaultVoteDirection, commentScore, isAuthorOP);
        commentViewHolder.itemView.setOnClickListener(v -> {
          commentClickStream.accept(CommentClickEvent.create(commentNode, commentViewHolder.itemView, !dankCommentNode.isCollapsed()));
        });

        SwipeableLayout swipeableLayout = commentViewHolder.getSwipeableLayout();
        swipeableLayout.setSwipeActions(swipeActionsProvider.getSwipeActions());
        swipeableLayout.setOnPerformSwipeActionListener(action -> {
          swipeActionsProvider.performSwipeAction(action, commentNode, swipeableLayout);

          // We should ideally only be updating the backing data-set and let onBind() handle the
          // changes, but RecyclerView's item animator reset's the View's x-translation which we
          // don't want. So we manually update the Views here.
          onBindViewHolder(holder, holder.getAdapterPosition() - 1 /* -1 for parent adapter's offset for header item. */);
        });
        break;

      case REPLY:
        CommentReplyItem commentReplyItem = (CommentReplyItem) commentItem;
        ((ReplyViewHolder) holder).bind(commentReplyItem, replyActionsListener);
        break;

      case LOAD_MORE_COMMENTS:
        LoadMoreCommentItem loadMoreItem = ((LoadMoreCommentItem) commentItem);
        ((LoadMoreCommentViewHolder) holder).bind(loadMoreItem);

        holder.itemView.setOnClickListener(__ -> {
          loadMoreCommentsClickStream.accept(LoadMoreCommentsClickEvent.create(loadMoreItem.parentCommentNode(), holder.itemView));
        });
        break;

      default:
        throw new UnsupportedOperationException();
    }
  }

  @Override
  public long getItemId(int position) {
    return getItem(position).id();
  }

  public static class UserCommentViewHolder extends RecyclerView.ViewHolder implements ViewHolderWithSwipeActions {
    @BindView(R.id.item_comment_indented_container) IndentedLayout indentedContainer;
    @BindView(R.id.item_comment_byline) TextView bylineView;
    @BindView(R.id.item_comment_body) TextView commentBodyView;
    @BindView(R.id.item_comment_separator) View separatorView;

    @BindColor(R.color.submission_comment_byline_author) int bylineAuthorNameColor;
    @BindColor(R.color.submission_comment_byline_author_op) int bylineAuthorNameColorForOP;
    @BindColor(R.color.submission_comment_body_expanded) int expandedBodyColor;
    @BindColor(R.color.submission_comment_body_collapsed) int collapsedBodyColor;
    @BindColor(R.color.submission_comment_byline_default_color) int bylineDefaultColor;
    @BindString(R.string.submission_comment_byline_item_separator) String bylineItemSeparator;
    @BindString(R.string.submission_comment_byline_item_score) String bylineItemScoreString;

    private BetterLinkMovementMethod linkMovementMethod;
    private boolean isCollapsed;

    public static UserCommentViewHolder create(LayoutInflater inflater, ViewGroup parent, BetterLinkMovementMethod linkMovementMethod) {
      return new UserCommentViewHolder(inflater.inflate(R.layout.list_item_comment, parent, false), linkMovementMethod);
    }

    public UserCommentViewHolder(View itemView, BetterLinkMovementMethod linkMovementMethod) {
      super(itemView);
      ButterKnife.bind(this, itemView);
      this.linkMovementMethod = linkMovementMethod;

      // Bug workaround: TextView with clickable spans consume all touch events. Manually
      // transfer them to the parent so that the background touch indicator shows up +
      // click listener works.
      commentBodyView.setOnTouchListener((__, event) -> {
        if (isCollapsed) {
          return false;
        }
        boolean handledByMovementMethod = linkMovementMethod.onTouchEvent(commentBodyView, (Spannable) commentBodyView.getText(), event);
        return handledByMovementMethod || itemView.onTouchEvent(event);
      });
    }

    public void bind(DankCommentNode dankCommentNode, VoteDirection voteDirection, int commentScore, boolean isAuthorOP) {
      indentedContainer.setIndentationDepth(dankCommentNode.commentNode().getDepth() - 1);
      Comment comment = dankCommentNode.commentNode().getComment();
      isCollapsed = dankCommentNode.isCollapsed();  // Storing as an instance field so that it can be used in UserCommentViewHolder().

      // Byline: author, flair, score and timestamp.
      Truss bylineBuilder = new Truss();
      if (isCollapsed) {
        bylineBuilder.append(comment.getAuthor());
        bylineBuilder.append(bylineItemSeparator);

        // TODO: getTotalSize() is buggy. See: https://github.com/thatJavaNerd/JRAW/issues/189
        int hiddenCommentsCount = dankCommentNode.commentNode().getTotalSize() + 1;   // +1 for the parent comment itself.
        String hiddenCommentsString = itemView.getResources().getQuantityString(R.plurals.submission_comment_hidden_comments, hiddenCommentsCount);
        bylineBuilder.append(String.format(hiddenCommentsString, hiddenCommentsCount));

      } else {
        bylineBuilder.pushSpan(new ForegroundColorSpan(isAuthorOP ? bylineAuthorNameColorForOP : bylineAuthorNameColor));
        bylineBuilder.append(comment.getAuthor());
        bylineBuilder.popSpan();
        Flair authorFlair = comment.getAuthorFlair();
        if (authorFlair != null) {
          bylineBuilder.append(bylineItemSeparator);
          bylineBuilder.append(authorFlair.getText());
        }
        bylineBuilder.append(bylineItemSeparator);
        bylineBuilder.pushSpan(new ForegroundColorSpan(ContextCompat.getColor(itemView.getContext(), Commons.voteColor(voteDirection))));
        bylineBuilder.append(String.format(bylineItemScoreString, Strings.abbreviateScore(commentScore)));
        bylineBuilder.popSpan();
        bylineBuilder.append(bylineItemSeparator);
        bylineBuilder.append(Dates.createTimestamp(itemView.getResources(), JrawUtils.createdTimeUtc(comment)));
      }
      bylineView.setTextColor(isCollapsed ? collapsedBodyColor : bylineDefaultColor);
      bylineView.setText(bylineBuilder.build());

      // Body.
      String commentBody = comment.getDataNode().get("body_html").asText();
      if (isCollapsed) {
        commentBodyView.setText(Markdown.stripMarkdown(commentBody));
        commentBodyView.setMovementMethod(null);
      } else {
        commentBodyView.setText(Markdown.parseRedditMarkdownHtml(commentBody, commentBodyView.getPaint()));
        commentBodyView.setMovementMethod(linkMovementMethod);
      }
      commentBodyView.setMaxLines(isCollapsed ? 1 : Integer.MAX_VALUE);
      commentBodyView.setEllipsize(isCollapsed ? TextUtils.TruncateAt.END : null);
      commentBodyView.setTextColor(isCollapsed ? collapsedBodyColor : expandedBodyColor);
    }

    @Override
    public SwipeableLayout getSwipeableLayout() {
      return (SwipeableLayout) itemView;
    }
  }

  public static class ReplyViewHolder extends RecyclerView.ViewHolder {
    @BindView(R.id.item_comment_reply_indented_container) IndentedLayout indentedLayout;
    @BindView(R.id.item_comment_reply_discard) ImageButton discardButton;
    @BindView(R.id.item_comment_reply_go_fullscreen) ImageButton goFullscreenButton;
    @BindView(R.id.item_comment_reply_send) ImageButton sendButton;
    @BindView(R.id.item_comment_reply_message) EditText replyMessageField;

    public static RecyclerView.ViewHolder create(LayoutInflater inflater, ViewGroup parent) {
      return new ReplyViewHolder(inflater.inflate(R.layout.list_item_comment_reply, parent, false));
    }

    public ReplyViewHolder(View itemView) {
      super(itemView);
      ButterKnife.bind(this, itemView);
    }

    public void bind(CommentReplyItem commentReplyItem, ReplyActionsListener replyActionsListener) {
      CommentNode commentNodeToReply = commentReplyItem.commentNodeToReply();
      indentedLayout.setIndentationDepth(commentNodeToReply.getDepth());

      discardButton.setOnClickListener(o -> replyActionsListener.onClickDiscardReply(commentNodeToReply));
      goFullscreenButton.setOnClickListener(o -> replyActionsListener.onClickEditReplyInFullscreenMode(commentNodeToReply));
      sendButton.setOnClickListener(o -> replyActionsListener.onClickSendReply(commentNodeToReply, replyMessageField.getText().toString()));
    }
  }

  public static class LoadMoreCommentViewHolder extends RecyclerView.ViewHolder {
    @BindView(R.id.item_loadmorecomments_load_more) TextView loadMoreView;
    @BindView(R.id.item_loadmorecomments_indented_container) IndentedLayout indentedContainer;

    public static LoadMoreCommentViewHolder create(LayoutInflater inflater, ViewGroup parent) {
      return new LoadMoreCommentViewHolder(inflater.inflate(R.layout.list_item_load_more_comments, parent, false));
    }

    public LoadMoreCommentViewHolder(View itemView) {
      super(itemView);
      ButterKnife.bind(this, itemView);
    }

    public void bind(LoadMoreCommentItem loadMoreCommentsItem) {
      CommentNode parentCommentNode = loadMoreCommentsItem.parentCommentNode();

      indentedContainer.setIndentationDepth(parentCommentNode.getDepth());

      if (loadMoreCommentsItem.progressVisible()) {
        loadMoreView.setText(R.string.submission_loading_more_comments);
      } else {
        if (parentCommentNode.isThreadContinuation()) {
          loadMoreView.setText(R.string.submission_continue_this_thread);
        } else {
          loadMoreView.setText(itemView.getResources().getString(
              R.string.submission_load_more_comments,
              parentCommentNode.getMoreChildren().getCount()
          ));
        }
      }
      Views.setCompoundDrawableEnd(loadMoreView, parentCommentNode.isThreadContinuation() ? R.drawable.ic_arrow_forward_12dp : 0);
    }
  }
}
