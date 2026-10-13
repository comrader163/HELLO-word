/*
 * MiNotes（小米便签社区开源版）核心源码精读标注
 * 原文件：MiCode/Notes 仓库 src/net/micode/notes/ui/NoteEditActivity.java
 * 说明：本文件为精读学习版本，保留全部原始代码，新增中文注释以标注业务逻辑
 */

package net.micode.notes.ui;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.appwidget.AppWidgetManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.TextNote;
import net.micode.notes.model.WorkingNote;
import net.micode.notes.model.WorkingNote.NoteSettingChangedListener;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.ResourceParser;
import net.micode.notes.tool.ResourceParser.TextAppearanceResources;
import net.micode.notes.ui.DateTimePickerDialog.OnDateTimeSetListener;
import net.micode.notes.ui.NoteEditText.OnTextViewChangeListener;
import net.micode.notes.widget.NoteWidgetProvider_2x;
import net.micode.notes.widget.NoteWidgetProvider_4x;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NoteEditActivity：笔记编辑界面 Activity
 * 核心职责：
 *  1. 展示并编辑单条笔记内容（正文、背景色、字体大小、闹钟提醒）
 *  2. 支持"普通模式 / 清单(check list)模式"两种编辑方式切换
 *  3. 通过 WorkingNote（工作笔记模型）读写数据库，并与桌面小部件联动
 * 实现方式：继承 Activity，实现三个监听接口——
 *  OnClickListener（点击事件）、NoteSettingChangedListener（笔记设置变更）、
 *  OnTextViewChangeListener（文本变化监听）
 */
public class NoteEditActivity extends Activity implements OnClickListener,
        NoteSettingChangedListener, OnTextViewChangeListener {
    /**
     * HeadViewHolder：头部视图缓存（ViewHolder 模式）
     * 作用：避免每次 getViewById 都遍历视图树，提升列表滚动性能
     */
    private class HeadViewHolder {
        public TextView tvModified;      // 最后修改时间文本

        public ImageView ivAlertIcon;    // 闹钟提醒图标

        public TextView tvAlertDate;     // 提醒时间文本

        public ImageView ibSetBgColor;   // 设置背景色的按钮
    }

    // 背景色按钮 ID → 背景色枚举值 的映射表（UI控件与业务值解耦）
    private static final Map<Integer, Integer> sBgSelectorBtnsMap = new HashMap<Integer, Integer>();
    static {
        sBgSelectorBtnsMap.put(R.id.iv_bg_yellow, ResourceParser.YELLOW);
        sBgSelectorBtnsMap.put(R.id.iv_bg_red, ResourceParser.RED);
        sBgSelectorBtnsMap.put(R.id.iv_bg_blue, ResourceParser.BLUE);
        sBgSelectorBtnsMap.put(R.id.iv_bg_green, ResourceParser.GREEN);
        sBgSelectorBtnsMap.put(R.id.iv_bg_white, ResourceParser.WHITE);
    }

    // 背景色枚举值 → 选中态按钮控件 ID 的映射表（用于高亮当前选中色）
    private static final Map<Integer, Integer> sBgSelectorSelectionMap = new HashMap<Integer, Integer>();
    static {
        sBgSelectorSelectionMap.put(ResourceParser.YELLOW, R.id.iv_bg_yellow_select);
        sBgSelectorSelectionMap.put(ResourceParser.RED, R.id.iv_bg_red_select);
        sBgSelectorSelectionMap.put(ResourceParser.BLUE, R.id.iv_bg_blue_select);
        sBgSelectorSelectionMap.put(ResourceParser.GREEN, R.id.iv_bg_green_select);
        sBgSelectorSelectionMap.put(ResourceParser.WHITE, R.id.iv_bg_white_select);
    }

    // 字号按钮 ID → 字号枚举值的映射表
    private static final Map<Integer, Integer> sFontSizeBtnsMap = new HashMap<Integer, Integer>();
    static {
        sFontSizeBtnsMap.put(R.id.ll_font_large, ResourceParser.TEXT_LARGE);
        sFontSizeBtnsMap.put(R.id.ll_font_small, ResourceParser.TEXT_SMALL);
        sFontSizeBtnsMap.put(R.id.ll_font_normal, ResourceParser.TEXT_MEDIUM);
        sFontSizeBtnsMap.put(R.id.ll_font_super, ResourceParser.TEXT_SUPER);
    }

    // 字号枚举值 → 选中态控件 ID 的映射表
    private static final Map<Integer, Integer> sFontSelectorSelectionMap = new HashMap<Integer, Integer>();
    static {
        sFontSelectorSelectionMap.put(ResourceParser.TEXT_LARGE, R.id.iv_large_select);
        sFontSelectorSelectionMap.put(ResourceParser.TEXT_SMALL, R.id.iv_small_select);
        sFontSelectorSelectionMap.put(ResourceParser.TEXT_MEDIUM, R.id.iv_medium_select);
        sFontSelectorSelectionMap.put(ResourceParser.TEXT_SUPER, R.id.iv_super_select);
    }

    // 日志标签：统一使用类名，便于 Logcat 过滤定位
    private static final String TAG = "NoteEditActivity";

    private HeadViewHolder mNoteHeaderHolder;  // 头部视图缓存

    private View mHeadViewPanel;               // 头部面板（背景色随笔记变化）

    private View mNoteBgColorSelector;         // 背景色选择器弹层

    private View mFontSizeSelector;            // 字号选择器弹层

    private EditText mNoteEditor;              // 普通模式下的正文编辑框

    private View mNoteEditorPanel;             // 正文编辑面板

    private WorkingNote mWorkingNote;          // 工作笔记模型（内存中的当前笔记）

    private SharedPreferences mSharedPrefs;    // 偏好设置（用于持久化用户选择的字号）
    private int mFontSizeId;                   // 当前字号资源 ID

    private static final String PREFERENCE_FONT_SIZE = "pref_font_size";  // 字号偏好键

    private static final int SHORTCUT_ICON_TITLE_MAX_LEN = 10;            // 桌面快捷方式标题最大长度

    public static final String TAG_CHECKED = String.valueOf('\u221A');    // 清单模式"已完成"标记(√)
    public static final String TAG_UNCHECKED = String.valueOf('\u25A1');  // 清单模式"未完成"标记(□)

    private LinearLayout mEditTextList;        // 清单模式下的编辑行列表容器

    private String mUserQuery;                 // 用户搜索词（用于高亮显示）
    private Pattern mPattern;                  // 搜索词正则

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.note_edit);

        // 首次创建：根据 Intent 初始化笔记状态；若初始化失败则直接关闭页面
        if (savedInstanceState == null && !initActivityState(getIntent())) {
            finish();
            return;
        }
        initResources();
    }

    /**
     * 系统内存不足杀死 Activity 后恢复时调用：
     * 从保存的状态中取出笔记 ID，重新加载笔记，保证用户编辑不丢失
     */
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState != null && savedInstanceState.containsKey(Intent.EXTRA_UID)) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.putExtra(Intent.EXTRA_UID, savedInstanceState.getLong(Intent.EXTRA_UID));
            if (!initActivityState(intent)) {
                finish();
                return;
            }
            Log.d(TAG, "Restoring from killed activity");
        }
    }

    /**
     * initActivityState：根据 Intent 动作初始化笔记状态
     * ACTION_VIEW        → 打开已有笔记（可能来自搜索结果，此时带 mUserQuery 高亮词）
     * ACTION_INSERT_OR_EDIT → 新建笔记 / 从通话记录创建"通话便签"
     * 返回值：false 表示初始化失败，调用方应结束页面
     */
    private boolean initActivityState(Intent intent) {
        mWorkingNote = null;
        if (TextUtils.equals(Intent.ACTION_VIEW, intent.getAction())) {
            long noteId = intent.getLongExtra(Intent.EXTRA_UID, 0);
            mUserQuery = "";

            // 若从搜索结果进入，则解析真实笔记 ID 与搜索关键词
            if (intent.hasExtra(SearchManager.EXTRA_DATA_KEY)) {
                noteId = Long.parseLong(intent.getStringExtra(SearchManager.EXTRA_DATA_KEY));
                mUserQuery = intent.getStringExtra(SearchManager.USER_QUERY);
            }

            // 校验笔记在数据库中是否可见；不可见（已删除）则跳回列表页并提示
            if (!DataUtils.visibleInNoteDatabase(getContentResolver(), noteId, Notes.TYPE_NOTE)) {
                Intent jump = new Intent(this, NotesListActivity.class);
                startActivity(jump);
                showToast(R.string.error_note_not_exist);
                finish();
                return false;
            } else {
                mWorkingNote = WorkingNote.load(this, noteId);
                if (mWorkingNote == null) {
                    Log.e(TAG, "load note failed with note id" + noteId);
                    finish();
                    return false;
                }
            }
            // 隐藏软键盘并调整窗口大小，避免遮挡正文
            getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        } else if(TextUtils.equals(Intent.ACTION_INSERT_OR_EDIT, intent.getAction())) {
            // 新建笔记分支：读取 Intent 携带的文件夹ID、桌面小部件ID/类型、背景色
            long folderId = intent.getLongExtra(Notes.INTENT_EXTRA_FOLDER_ID, 0);
            int widgetId = intent.getIntExtra(Notes.INTENT_EXTRA_WIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            int widgetType = intent.getIntExtra(Notes.INTENT_EXTRA_WIDGET_TYPE,
                    Notes.TYPE_WIDGET_INVALIDE);
            int bgResId = intent.getIntExtra(Notes.INTENT_EXTRA_BACKGROUND_ID,
                    ResourceParser.getDefaultBgId(this));

            // 解析"通话记录便签"参数：电话号码 + 通话时间
            String phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            long callDate = intent.getLongExtra(Notes.INTENT_EXTRA_CALL_DATE, 0);
            if (callDate != 0 && phoneNumber != null) {
                if (TextUtils.isEmpty(phoneNumber)) {
                    Log.w(TAG, "The call record number is null");
                }
                long noteId = 0;
                // 若同一号码同一时间已存在便签，则加载已存在笔记；否则新建
                if ((noteId = DataUtils.getNoteIdByPhoneNumberAndCallDate(getContentResolver(),
                        phoneNumber, callDate)) > 0) {
                    mWorkingNote = WorkingNote.load(this, noteId);
                    if (mWorkingNote == null) {
                        Log.e(TAG, "load call note failed with note id" + noteId);
                        finish();
                        return false;
                    }
                } else {
                    mWorkingNote = WorkingNote.createEmptyNote(this, folderId, widgetId,
                            widgetType, bgResId);
                    mWorkingNote.convertToCallNote(phoneNumber, callDate);
                }
            } else {
                // 普通新建笔记
                mWorkingNote = WorkingNote.createEmptyNote(this, folderId, widgetId, widgetType,
                        bgResId);
            }

            // 新建场景自动弹出软键盘
            getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        } else {
            Log.e(TAG, "Intent not specified action, should not support");
            finish();
            return false;
        }
        // 注册监听：笔记设置（背景色/闹钟/小部件）变化时回调刷新界面
        mWorkingNote.setOnSettingStatusChangedListener(this);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        initNoteScreen();
    }

    /**
     * initNoteScreen：刷新编辑界面显示
     * 包括：应用字号、切换普通/清单编辑模式、高亮搜索结果、刷新背景色与修改时间
     */
    private void initNoteScreen() {
        mNoteEditor.setTextAppearance(this, TextAppearanceResources
                .getTexAppearanceResource(mFontSizeId));
        if (mWorkingNote.getCheckListMode() == TextNote.MODE_CHECK_LIST) {
            // 清单模式：按行拆分为多个编辑框
            switchToListMode(mWorkingNote.getContent());
        } else {
            // 普通模式：全文编辑，并高亮搜索命中词
            mNoteEditor.setText(getHighlightQueryResult(mWorkingNote.getContent(), mUserQuery));
            mNoteEditor.setSelection(mNoteEditor.getText().length());
        }
        // 隐藏所有"选中态"标记，再单独显示当前背景色对应的选中标记
        for (Integer id : sBgSelectorSelectionMap.keySet()) {
            findViewById(sBgSelectorSelectionMap.get(id)).setVisibility(View.GONE);
        }
        mHeadViewPanel.setBackgroundResource(mWorkingNote.getTitleBgResId());
        mNoteEditorPanel.setBackgroundResource(mWorkingNote.getBgColorResId());

        // 显示最后修改时间（含年月日时分）
        mNoteHeaderHolder.tvModified.setText(DateUtils.formatDateTime(this,
                mWorkingNote.getModifiedDate(), DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_NUMERIC_DATE | DateUtils.FORMAT_SHOW_TIME
                        | DateUtils.FORMAT_SHOW_YEAR));

        showAlertHeader();
    }

    /**
     * showAlertHeader：展示闹钟提醒头部
     * 未设置提醒 → 隐藏提醒区域；已过期 → 显示"已过期"；未过期 → 显示相对时间
     */
    private void showAlertHeader() {
        if (mWorkingNote.hasClockAlert()) {
            long time = System.currentTimeMillis();
            if (time > mWorkingNote.getAlertDate()) {
                mNoteHeaderHolder.tvAlertDate.setText(R.string.note_alert_expired);
            } else {
                mNoteHeaderHolder.tvAlertDate.setText(DateUtils.getRelativeTimeSpanString(
                        mWorkingNote.getAlertDate(), time, DateUtils.MINUTE_IN_MILLIS));
            }
            mNoteHeaderHolder.tvAlertDate.setVisibility(View.VISIBLE);
            mNoteHeaderHolder.ivAlertIcon.setVisibility(View.VISIBLE);
        } else {
            mNoteHeaderHolder.tvAlertDate.setVisibility(View.GONE);
            mNoteHeaderHolder.ivAlertIcon.setVisibility(View.GONE);
        };
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        initActivityState(intent);
    }

    /**
     * 保存活动状态：若新笔记尚未入库，先保存生成 ID；再把笔记 ID 写入 outState
     * 这样即使进程被杀死，恢复时也能重新加载当前笔记
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (!mWorkingNote.existInDatabase()) {
            saveNote();
        }
        outState.putLong(Intent.EXTRA_UID, mWorkingNote.getNoteId());
        Log.d(TAG, "Save working note id: " + mWorkingNote.getNoteId() + " onSaveInstanceState");
    }

    /**
     * 触摸事件分发：当背景色/字号选择器弹层可见，且点击发生在弹层之外时，自动收起弹层
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mNoteBgColorSelector.getVisibility() == View.VISIBLE
                && !inRangeOfView(mNoteBgColorSelector, ev)) {
            mNoteBgColorSelector.setVisibility(View.GONE);
            return true;
        }

        if (mFontSizeSelector.getVisibility() == View.VISIBLE
                && !inRangeOfView(mFontSizeSelector, ev)) {
            mFontSizeSelector.setVisibility(View.GONE);
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 判断触摸点 (ev) 是否落在指定 View 的屏幕范围内 */
    private boolean inRangeOfView(View view, MotionEvent ev) {
        int []location = new int[2];
        view.getLocationOnScreen(location);
        int x = location[0];
        int y = location[1];
        if (ev.getX() < x
                || ev.getX() > (x + view.getWidth())
                || ev.getY() < y
                || ev.getY() > (y + view.getHeight())) {
                    return false;
                }
        return true;
    }

    /**
     * initResources：初始化所有视图控件引用
     * 同时从 SharedPreferences 读取用户保存的字号；若资源 ID 越界则回退默认字号
     */
    private void initResources() {
        mHeadViewPanel = findViewById(R.id.note_title);
        mNoteHeaderHolder = new HeadViewHolder();
        mNoteHeaderHolder.tvModified = (TextView) findViewById(R.id.tv_modified_date);
        mNoteHeaderHolder.ivAlertIcon = (ImageView) findViewById(R.id.iv_alert_icon);
        mNoteHeaderHolder.tvAlertDate = (TextView) findViewById(R.id.tv_alert_date);
        mNoteHeaderHolder.ibSetBgColor = (ImageView) findViewById(R.id.btn_set_bg_color);
        mNoteHeaderHolder.ibSetBgColor.setOnClickListener(this);
        mNoteEditor = (EditText) findViewById(R.id.note_edit_view);
        mNoteEditorPanel = findViewById(R.id.sv_note_edit);
        mNoteBgColorSelector = findViewById(R.id.note_bg_color_selector);
        for (int id : sBgSelectorBtnsMap.keySet()) {
            ImageView iv = (ImageView) findViewById(id);
            iv.setOnClickListener(this);
        }

        mFontSizeSelector = findViewById(R.id.font_size_selector);
        for (int id : sFontSizeBtnsMap.keySet()) {
            View view = findViewById(id);
            view.setOnClickListener(this);
        };
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mFontSizeId = mSharedPrefs.getInt(PREFERENCE_FONT_SIZE, ResourceParser.BG_DEFAULT_FONT_SIZE);
        // 防御性编程：若历史存储的资源 ID 已失效，回退到默认字号
        if(mFontSizeId >= TextAppearanceResources.getResourcesSize()) {
            mFontSizeId = ResourceParser.BG_DEFAULT_FONT_SIZE;
        }
        mEditTextList = (LinearLayout) findViewById(R.id.note_edit_list);
    }

    /** 页面暂停（如切后台）：保存笔记并收起所有设置弹层 */
    @Override
    protected void onPause() {
        super.onPause();
        if(saveNote()) {
            Log.d(TAG, "Note data was saved with length:" + mWorkingNote.getContent().length());
        }
        clearSettingState();
    }

    /**
     * updateWidget：通知桌面小部件刷新
     * 根据小部件类型（2x/4x）找到对应 Provider，发送 ACTION_APPWIDGET_UPDATE 广播
     */
    private void updateWidget() {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        if (mWorkingNote.getWidgetType() == Notes.TYPE_WIDGET_2X) {
            intent.setClass(this, NoteWidgetProvider_2x.class);
        } else if (mWorkingNote.getWidgetType() == Notes.TYPE_WIDGET_4X) {
            intent.setClass(this, NoteWidgetProvider_4x.class);
        } else {
            Log.e(TAG, "Unspported widget type");
            return;
        }

        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] {
            mWorkingNote.getWidgetId()
        });

        sendBroadcast(intent);
        setResult(RESULT_OK, intent);
    }

    /**
     * 点击事件统一入口：
     *  - btn_set_bg_color：展开背景色选择器
     *  - 背景色按钮：切换笔记背景色并收起选择器
     *  - 字号按钮：切换字号、持久化偏好并刷新正文外观
     */
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_set_bg_color) {
            mNoteBgColorSelector.setVisibility(View.VISIBLE);
            findViewById(sBgSelectorSelectionMap.get(mWorkingNote.getBgColorId())).setVisibility(
                    View.VISIBLE);
        } else if (sBgSelectorBtnsMap.containsKey(id)) {
            findViewById(sBgSelectorSelectionMap.get(mWorkingNote.getBgColorId())).setVisibility(
                    View.GONE);
            mWorkingNote.setBgColorId(sBgSelectorBtnsMap.get(id));
            mNoteBgColorSelector.setVisibility(View.GONE);
        } else if (sFontSizeBtnsMap.containsKey(id)) {
            findViewById(sFontSelectorSelectionMap.get(mFontSizeId)).setVisibility(View.GONE);
            mFontSizeId = sFontSizeBtnsMap.get(id);
            mSharedPrefs.edit().putInt(PREFERENCE_FONT_SIZE, mFontSizeId).commit();
            findViewById(sFontSelectorSelectionMap.get(mFontSizeId)).setVisibility(View.VISIBLE);
            if (mWorkingNote.getCheckListMode() == TextNote.MODE_CHECK_LIST) {
                getWorkingText();
                switchToListMode(mWorkingNote.getContent());
            } else {
                mNoteEditor.setTextAppearance(this,
                        TextAppearanceResources.getTexAppearanceResource(mFontSizeId));
            }
            mFontSizeSelector.setVisibility(View.GONE);
        }
    }

    /** 返回键处理：先收起弹层，再保存笔记 */
    @Override
    public void onBackPressed() {
        if(clearSettingState()) {
            return;
        }

        saveNote();
        super.onBackPressed();
    }

    /** 收起背景色/字号选择弹层；返回 true 表示已处理（阻止直接退出） */
    private boolean clearSettingState() {
        if (mNoteBgColorSelector.getVisibility() == View.VISIBLE) {
            mNoteBgColorSelector.setVisibility(View.GONE);
            return true;
        } else if (mFontSizeSelector.getVisibility() == View.VISIBLE) {
            mFontSizeSelector.setVisibility(View.GONE);
            return true;
        }
        return false;
    }

    /** 笔记背景色变化回调：刷新面板与头部背景、选中标记 */
    public void onBackgroundColorChanged() {
        findViewById(sBgSelectorSelectionMap.get(mWorkingNote.getBgColorId())).setVisibility(
                View.VISIBLE);
        mNoteEditorPanel.setBackgroundResource(mWorkingNote.getBgColorResId());
        mHeadViewPanel.setBackgroundResource(mWorkingNote.getTitleBgResId());
    }

    /**
     * 每次显示菜单前调用：根据笔记状态动态配置菜单项
     *  - 通话便签用专用菜单；清单模式切换菜单标题；设置过闹钟后隐藏"设置提醒"
     */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (isFinishing()) {
            return true;
        }
        clearSettingState();
        menu.clear();
        if (mWorkingNote.getFolderId() == Notes.ID_CALL_RECORD_FOLDER) {
            getMenuInflater().inflate(R.menu.call_note_edit, menu);
        } else {
            getMenuInflater().inflate(R.menu.note_edit, menu);
        }
        if (mWorkingNote.getCheckListMode() == TextNote.MODE_CHECK_LIST) {
            menu.findItem(R.id.menu_list_mode).setTitle(R.string.menu_normal_mode);
        } else {
            menu.findItem(R.id.menu_list_mode).setTitle(R.string.menu_list_mode);
        }
        if (mWorkingNote.hasClockAlert()) {
            menu.findItem(R.id.menu_alert).setVisible(false);
        } else {
            menu.findItem(R.id.menu_delete_remind).setVisible(false);
        }
        return true;
    }

    /** 菜单项选择：新建、删除、字号、清单切换、分享、发送到桌面、设置提醒等 */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_new_note:
                createNewNote();
                break;
            case R.id.menu_delete:
                // 删除前弹出确认对话框，防止误删
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(getString(R.string.alert_title_delete));
                builder.setIcon(android.R.drawable.ic_dialog_alert);
                builder.setMessage(getString(R.string.alert_message_delete_note));
                builder.setPositiveButton(android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                deleteCurrentNote();
                                finish();
                            }
                        });
                builder.setNegativeButton(android.R.string.cancel, null);
                builder.show();
                break;
            case R.id.menu_font_size:
                mFontSizeSelector.setVisibility(View.VISIBLE);
                findViewById(sFontSelectorSelectionMap.get(mFontSizeId)).setVisibility(View.VISIBLE);
                break;
            case R.id.menu_list_mode:
                // 在普通/清单模式间切换（0 表示普通模式）
                mWorkingNote.setCheckListMode(mWorkingNote.getCheckListMode() == 0 ?
                        TextNote.MODE_CHECK_LIST : 0);
                break;
            case R.id.menu_share:
                // 分享：取出当前文本，通过系统分享 Intent 发送
                getWorkingText();
                sendTo(this, mWorkingNote.getContent());
                break;
            case R.id.menu_send_to_desktop:
                sendToDesktop();
                break;
            case R.id.menu_alert:
                setReminder();
                break;
            case R.id.menu_delete_remind:
                mWorkingNote.setAlertDate(0, false);
                break;
            default:
                break;
        }
        return true;
    }

    /** 弹出日期时间选择对话框，选择后为笔记设置闹钟提醒 */
    private void setReminder() {
        DateTimePickerDialog d = new DateTimePickerDialog(this, System.currentTimeMillis());
        d.setOnDateTimeSetListener(new OnDateTimeSetListener() {
            public void OnDateTimeSet(AlertDialog dialog, long date) {
                mWorkingNote.setAlertDate(date, true);
            }
        });
        d.show();
    }

    /**
     * 分享笔记到支持 ACTION_SEND + text/plain 的应用
     */
    private void sendTo(Context context, String info) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, info);
        intent.setType("text/plain");
        context.startActivity(intent);
    }

    /** 新建笔记：先保存当前笔记，再启动新的编辑 Activity */
    private void createNewNote() {
        // Firstly, save current editing notes
        saveNote();

        // 关闭当前页面，启动新编辑页（保持同一文件夹）
        finish();
        Intent intent = new Intent(this, NoteEditActivity.class);
        intent.setAction(Intent.ACTION_INSERT_OR_EDIT);
        intent.putExtra(Notes.INTENT_EXTRA_FOLDER_ID, mWorkingNote.getFolderId());
        startActivity(intent);
    }

    /**
     * deleteCurrentNote：删除当前笔记
     * 同步模式（已绑定同步账号）→ 移入回收站；否则 → 物理删除
     */
    private void deleteCurrentNote() {
        if (mWorkingNote.existInDatabase()) {
            HashSet<Long> ids = new HashSet<Long>();
            long id = mWorkingNote.getNoteId();
            if (id != Notes.ID_ROOT_FOLDER) {
                ids.add(id);
            } else {
                Log.d(TAG, "Wrong note id, should not happen");
            }
            if (!isSyncMode()) {
                if (!DataUtils.batchDeleteNotes(getContentResolver(), ids)) {
                    Log.e(TAG, "Delete Note error");
                }
            } else {
                if (!DataUtils.batchMoveToFolder(getContentResolver(), ids, Notes.ID_TRASH_FOLER)) {
                    Log.e(TAG, "Move notes to trash folder error, should not happens");
                }
            }
        }
        mWorkingNote.markDeleted(true);
    }

    /** 判断当前是否处于同步模式（绑定了同步账号） */
    private boolean isSyncMode() {
        return NotesPreferenceActivity.getSyncAccountName(this).trim().length() > 0;
    }

    /**
     * 闹钟提醒变更回调：
     * 未入库的笔记先保存获取 ID，再用 AlarmManager 注册/取消系统闹钟
     */
    public void onClockAlertChanged(long date, boolean set) {
        if (!mWorkingNote.existInDatabase()) {
            saveNote();
        }
        if (mWorkingNote.getNoteId() > 0) {
            Intent intent = new Intent(this, AlarmReceiver.class);
            intent.setData(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, mWorkingNote.getNoteId()));
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
            AlarmManager alarmManager = ((AlarmManager) getSystemService(ALARM_SERVICE));
            showAlertHeader();
            if(!set) {
                alarmManager.cancel(pendingIntent);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, date, pendingIntent);
            }
        } else {
            Log.e(TAG, "Clock alert setting error");
            showToast(R.string.error_note_empty_for_clock);
        }
    }

    /** 小部件变化回调：刷新对应桌面小部件 */
    public void onWidgetChanged() {
        updateWidget();
    }

    /** 清单模式：删除第 index 行后，修正后续行序号并合并文本 */
    public void onEditTextDelete(int index, String text) {
        int childCount = mEditTextList.getChildCount();
        if (childCount == 1) {
            return;
        }

        for (int i = index + 1; i < childCount; i++) {
            ((NoteEditText) mEditTextList.getChildAt(i).findViewById(R.id.et_edit_text))
                    .setIndex(i - 1);
        }

        mEditTextList.removeViewAt(index);
        NoteEditText edit = null;
        if(index == 0) {
            edit = (NoteEditText) mEditTextList.getChildAt(0).findViewById(
                    R.id.et_edit_text);
        } else {
            edit = (NoteEditText) mEditTextList.getChildAt(index - 1).findViewById(
                    R.id.et_edit_text);
        }
        int length = edit.length();
        edit.append(text);
        edit.requestFocus();
        edit.setSelection(length);
    }

    /** 清单模式：在指定位置插入一个新的编辑行 */
    public void onEditTextEnter(int index, String text) {
        if(index > mEditTextList.getChildCount()) {
            Log.e(TAG, "Index out of mEditTextList boundrary, should not happen");
        }

        View view = getListItem(text, index);
        mEditTextList.addView(view, index);
        NoteEditText edit = (NoteEditText) view.findViewById(R.id.et_edit_text);
        edit.requestFocus();
        edit.setSelection(0);
        for (int i = index + 1; i < mEditTextList.getChildCount(); i++) {
            ((NoteEditText) mEditTextList.getChildAt(i).findViewById(R.id.et_edit_text))
                    .setIndex(i);
        }
    }

    /**
     * switchToListMode：切换到清单编辑模式
     * 将正文按换行拆分，每一行生成一个"复选框 + 编辑框"的列表项
     */
    private void switchToListMode(String text) {
        mEditTextList.removeAllViews();
        String[] items = text.split("\n");
        int index = 0;
        for (String item : items) {
            if(!TextUtils.isEmpty(item)) {
                mEditTextList.addView(getListItem(item, index));
                index++;
            }
        }
        // 末尾追加一个空行，方便继续输入
        mEditTextList.addView(getListItem("", index));
        mEditTextList.getChildAt(index).findViewById(R.id.et_edit_text).requestFocus();

        mNoteEditor.setVisibility(View.GONE);
        mEditTextList.setVisibility(View.VISIBLE);
    }

    /**
     * getHighlightQueryResult：生成带搜索高亮的结果文本
     * 用 BackgroundColorSpan 给搜索命中的子串加背景色
     */
    private Spannable getHighlightQueryResult(String fullText, String userQuery) {
        SpannableString spannable = new SpannableString(fullText == null ? "" : fullText);
        if (!TextUtils.isEmpty(userQuery)) {
            mPattern = Pattern.compile(userQuery);
            Matcher m = mPattern.matcher(fullText);
            int start = 0;
            while (m.find(start)) {
                spannable.setSpan(
                        new BackgroundColorSpan(this.getResources().getColor(
                                R.color.user_query_highlight)), m.start(), m.end(),
                        Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
                start = m.end();
            }
        }
        return spannable;
    }

    /**
     * getListItem：构造清单模式的单行视图
     * 若行首以 √/□ 开头则设置复选框初始状态与删除线样式
     */
    private View getListItem(String item, int index) {
        View view = LayoutInflater.from(this).inflate(R.layout.note_edit_list_item, null);
        final NoteEditText edit = (NoteEditText) view.findViewById(R.id.et_edit_text);
        edit.setTextAppearance(this, TextAppearanceResources.getTexAppearanceResource(mFontSizeId));
        CheckBox cb = ((CheckBox) view.findViewById(R.id.cb_edit_item));
        cb.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    edit.setPaintFlags(edit.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                } else {
                    edit.setPaintFlags(Paint.ANTI_ALIAS_FLAG | Paint.DEV_KERN_TEXT_FLAG);
                }
            }
        });

        // 根据行首标记决定初始勾选状态与删除线样式
        if (item.startsWith(TAG_CHECKED)) {
            cb.setChecked(true);
            edit.setPaintFlags(edit.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            item = item.substring(TAG_CHECKED.length(), item.length()).trim();
        } else if (item.startsWith(TAG_UNCHECKED)) {
            cb.setChecked(false);
            edit.setPaintFlags(Paint.ANTI_ALIAS_FLAG | Paint.DEV_KERN_TEXT_FLAG);
            item = item.substring(TAG_UNCHECKED.length(), item.length()).trim();
        }

        edit.setOnTextViewChangeListener(this);
        edit.setIndex(index);
        edit.setText(getHighlightQueryResult(item, mUserQuery));
        return view;
    }

    /** 文本内容变化：有文字时显示复选框，无文字时隐藏 */
    public void onTextChange(int index, boolean hasText) {
        if (index >= mEditTextList.getChildCount()) {
            Log.e(TAG, "Wrong index, should not happen");
            return;
        }
        if(hasText) {
            mEditTextList.getChildAt(index).findViewById(R.id.cb_edit_item).setVisibility(View.VISIBLE);
        } else {
            mEditTextList.getChildAt(index).findViewById(R.id.cb_edit_item).setVisibility(View.GONE);
        }
    }

    /** 清单模式切换回调：在两种编辑视图之间切换 */
    public void onCheckListModeChanged(int oldMode, int newMode) {
        if (newMode == TextNote.MODE_CHECK_LIST) {
            switchToListMode(mNoteEditor.getText().toString());
        } else {
            if (!getWorkingText()) {
                mWorkingNote.setWorkingText(mWorkingNote.getContent().replace(TAG_UNCHECKED + " ",
                        ""));
            }
            mNoteEditor.setText(getHighlightQueryResult(mWorkingNote.getContent(), mUserQuery));
            mEditTextList.setVisibility(View.GONE);
            mNoteEditor.setVisibility(View.VISIBLE);
        }
    }

    /**
     * getWorkingText：从编辑界面收集文本回写 WorkingNote
     * 清单模式会保留每行的 √/□ 状态标记；返回是否含有已完成项
     */
    private boolean getWorkingText() {
        boolean hasChecked = false;
        if (mWorkingNote.getCheckListMode() == TextNote.MODE_CHECK_LIST) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mEditTextList.getChildCount(); i++) {
                View view = mEditTextList.getChildAt(i);
                NoteEditText edit = (NoteEditText) view.findViewById(R.id.et_edit_text);
                if (!TextUtils.isEmpty(edit.getText())) {
                    if (((CheckBox) view.findViewById(R.id.cb_edit_item)).isChecked()) {
                        sb.append(TAG_CHECKED).append(" ").append(edit.getText()).append("\n");
                        hasChecked = true;
                    } else {
                        sb.append(TAG_UNCHECKED).append(" ").append(edit.getText()).append("\n");
                    }
                }
            }
            mWorkingNote.setWorkingText(sb.toString());
        } else {
            mWorkingNote.setWorkingText(mNoteEditor.getText().toString());
        }
        return hasChecked;
    }

    /**
     * saveNote：保存当前笔记
     * 保存成功时设置 RESULT_OK，供列表页判断"编辑后返回需刷新"
     */
    private boolean saveNote() {
        getWorkingText();
        boolean saved = mWorkingNote.saveNote();
        if (saved) {
            setResult(RESULT_OK);
        }
        return saved;
    }

    /**
     * sendToDesktop：把笔记创建为桌面快捷方式
     * 先确保笔记已入库，再发送 INSTALL_SHORTCUT 广播
     */
    private void sendToDesktop() {
        if (!mWorkingNote.existInDatabase()) {
            saveNote();
        }

        if (mWorkingNote.getNoteId() > 0) {
            Intent sender = new Intent();
            Intent shortcutIntent = new Intent(this, NoteEditActivity.class);
            shortcutIntent.setAction(Intent.ACTION_VIEW);
            shortcutIntent.putExtra(Intent.EXTRA_UID, mWorkingNote.getNoteId());
            sender.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
            sender.putExtra(Intent.EXTRA_SHORTCUT_NAME,
                    makeShortcutIconTitle(mWorkingNote.getContent()));
            sender.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(this, R.drawable.icon_app));
            sender.putExtra("duplicate", true);
            sender.setAction("com.android.launcher.action.INSTALL_SHORTCUT");
            showToast(R.string.info_note_enter_desktop);
            sendBroadcast(sender);
        } else {
            Log.e(TAG, "Send to desktop error");
            showToast(R.string.error_note_empty_for_send_to_desktop);
        }
    }

    /** 生成桌面快捷方式标题：去除清单标记并截断到最大长度 */
    private String makeShortcutIconTitle(String content) {
        content = content.replace(TAG_CHECKED, "");
        content = content.replace(TAG_UNCHECKED, "");
        return content.length() > SHORTCUT_ICON_TITLE_MAX_LEN ? content.substring(0,
                SHORTCUT_ICON_TITLE_MAX_LEN) : content;
    }

    private void showToast(int resId) {
        showToast(resId, Toast.LENGTH_SHORT);
    }

    private void showToast(int resId, int duration) {
        Toast.makeText(this, resId, duration).show();
    }
}